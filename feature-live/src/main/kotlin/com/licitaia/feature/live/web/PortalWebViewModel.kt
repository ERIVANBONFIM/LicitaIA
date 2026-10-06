package com.licitaia.feature.live.web

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.licitaia.domain.model.AppSettings
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.PortalConnectionStatus
import com.licitaia.domain.repository.AuthRepository
import com.licitaia.domain.repository.PortalRepository
import com.licitaia.domain.repository.SettingsRepository
import com.licitaia.domain.security.Permission
import com.licitaia.domain.security.Rbac
import com.licitaia.feature.live.keepalive.PortalKeepAliveController
import dagger.hilt.android.lifecycle.HiltViewModel
import com.licitaia.domain.network.ConnectivityMonitor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PortalWebUiState(
    val portal: Portal? = null,
    /** null = sem sessão no app (não há empresa ativa). */
    val companyId: Long? = null,
    val status: PortalConnectionStatus = PortalConnectionStatus.DESCONECTADO,
    val lastLoginAt: Long? = null,
    val requiresLogin: Boolean = true,
    val canSignOut: Boolean = false,
    val busy: Boolean = false,
    val ready: Boolean = false,
    /** Última página da área logada guardada (já sanitizada) — usada só para abrir a aba. */
    val lastUrl: String? = null,
    /** "Manter sessão ativa" ligado para este portal/empresa. */
    val keepAliveOn: Boolean = false,
    val keepAliveMinutes: Int = AppSettings().portalKeepAliveMinutes,
    /** Há rede validada. Sem rede a tela mostra "Sem internet — sua sessão continua salva" e o status NÃO muda. */
    val online: Boolean = true,
)

/**
 * Estado da sessão do portal no navegador interno. Recebe apenas URLs navegadas, o fato de existirem
 * cookies e o booleano do [PortalWebPolicy.contentProbeScript] — nunca conteúdo de página nem credenciais.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PortalWebViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val auth: AuthRepository,
    private val portals: PortalRepository,
    private val settings: SettingsRepository,
    private val keepAlive: PortalKeepAliveController,
    private val connectivity: ConnectivityMonitor,
) : ViewModel() {

    val portal: Portal? = savedStateHandle.get<String>("portal")?.let { name -> Portal.entries.firstOrNull { it.name == name } }

    private val busy = MutableStateFlow(false)
    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    // Rastro mínimo da aba para a heurística (URLs apenas, sem query strings persistidas).
    private var lastUrl: String? = null
    private var lastWasLogin: Boolean? = null
    private var lastPrevWasLogin: Boolean? = null
    private val visitedHosts = linkedSetOf<String>()
    private var lastSavedResumeUrl: String? = null

    /**
     * Status recém-sinalizado nesta aba, até o banco refletir: evita alerta duplicado (history + finished +
     * checagens de conteúdo da mesma página) enquanto o Flow ainda mostra o status antigo.
     */
    private var optimisticStatus: PortalConnectionStatus? = null
    private var optimisticAt = 0L

    init {
        keepAlive.start()
    }

    val state: StateFlow<PortalWebUiState> = auth.session.flatMapLatest { session ->
        val p = portal
        if (session == null || p == null) {
            flowOf(PortalWebUiState(portal = p, companyId = null, ready = true))
        } else {
            val companyId = session.activeCompany.id
            val canSignOut = Rbac.can(session.user.role, Permission.OPERAR_SESSOES) || Rbac.can(session.user.role, Permission.GERENCIAR_EMPRESAS)
            val resume = flow { emit(runCatching { portals.lastWebUrl(companyId, p) }.getOrNull()) }
            combine(portals.observeSessions(companyId), busy, settings.settings, resume, connectivity.online) { sessions, b, st, last, online ->
                val ps = sessions.firstOrNull { it.portal == p }
                PortalWebUiState(
                    portal = p, companyId = companyId,
                    status = ps?.status ?: PortalConnectionStatus.DESCONECTADO,
                    lastLoginAt = ps?.lastLoginAt,
                    requiresLogin = PortalWebPolicy.rules(p).requiresLogin,
                    canSignOut = canSignOut, busy = b, ready = true,
                    lastUrl = last,
                    keepAliveOn = st.isPortalKeepAliveOn(companyId, p),
                    keepAliveMinutes = st.portalKeepAliveMinutes,
                    online = online,
                )
            }.catch { emit(PortalWebUiState(portal = p, companyId = companyId, ready = true, online = connectivity.isOnline)) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PortalWebUiState(portal = portal))

    private fun effectiveStatus(persisted: PortalConnectionStatus): PortalConnectionStatus {
        val o = optimisticStatus ?: return persisted
        if (o == persisted || System.currentTimeMillis() - optimisticAt > OPTIMISTIC_WINDOW_MS) {
            optimisticStatus = null
            return persisted
        }
        return o
    }

    /**
     * Chamado pela WebView em doUpdateVisitedHistory/onPageFinished e após o [PortalWebPolicy.contentProbeScript].
     * @param contentExpired resultado da checagem de conteúdo desta página (false = não avaliado / sem aviso).
     * @return BLOCKED quando a URL está fora da allowlist (a tela decide bloquear).
     */
    fun onNavigated(url: String, hasCookies: Boolean, contentExpired: Boolean = false, loadFailed: Boolean = false): PortalWebPolicy.Signal {
        val p = portal ?: return PortalWebPolicy.Signal.NONE
        val s = state.value
        val companyId = s.companyId ?: return PortalWebPolicy.Signal.NONE
        val online = connectivity.isOnline
        // Sem rede ou com a página em erro, nada é concluído e o rastro da aba não avança (a página de erro
        // não conta como "página anterior" para a próxima avaliação).
        if (!PortalWebPolicy.canConclude(online, loadFailed)) {
            if (!online || loadFailed) cancelPendingExpire()
            return if (PortalWebPolicy.isAllowed(p, url)) PortalWebPolicy.Signal.NONE else PortalWebPolicy.Signal.BLOCKED
        }
        val isLogin = PortalWebPolicy.isLoginPage(p, url)
        // Mesma URL avaliada mais de uma vez (history + finished + conteúdo): reaproveita o "anterior" da primeira avaliação.
        val prevWasLogin = if (url == lastUrl) lastPrevWasLogin else lastWasLogin
        val status = effectiveStatus(s.status)
        val signal = PortalWebPolicy.evaluate(p, url, hasCookies, prevWasLogin, status, contentExpired, online = online, loadFailed = loadFailed)
        if (signal != PortalWebPolicy.Signal.BLOCKED) {
            lastPrevWasLogin = prevWasLogin
            lastUrl = url
            lastWasLogin = isLogin
            PortalWebPolicy.host(url)?.let { visitedHosts += "https://$it/" }
        }
        when (signal) {
            PortalWebPolicy.Signal.CONNECTED -> { cancelPendingExpire(); mark(companyId, p, loggedIn = true) }
            PortalWebPolicy.Signal.EXPIRED -> scheduleExpire(companyId, p)
            else -> Unit
        }
        // "Voltar para onde estava": guarda a página da área logada (sanitizada), nunca com aviso de sessão encerrada.
        val loggedNow = signal == PortalWebPolicy.Signal.CONNECTED ||
            (signal == PortalWebPolicy.Signal.NONE && status == PortalConnectionStatus.CONECTADO && !contentExpired)
        if (loggedNow) rememberResumeUrl(companyId, p, url)
        return signal
    }

    private fun rememberResumeUrl(companyId: Long, p: Portal, url: String) {
        val clean = PortalWebPolicy.sanitizeResumeUrl(p, url) ?: return
        if (clean == lastSavedResumeUrl) return
        lastSavedResumeUrl = clean
        viewModelScope.launch { runCatching { portals.saveLastWebUrl(companyId, p, clean) } }
    }

    /**
     * Página de erro (onReceivedError do main frame, HTTP ≥ 500, timeout) — o redirecionamento ao login que a
     * originou não conta como sessão encerrada.
     */
    fun onLoadFailed() = cancelPendingExpire()

    private var pendingExpire: Job? = null

    /**
     * EXPIRED só é gravado depois de [EXPIRE_CONFIRM_MS] sem erro de carga e com rede: o redirecionamento ao login
     * causado por queda de conexão (SPA sem falar com o servidor, SSO sem rede) é descartado em [onLoadFailed].
     */
    private fun scheduleExpire(companyId: Long, p: Portal) {
        if (pendingExpire?.isActive == true) return
        pendingExpire = viewModelScope.launch {
            delay(EXPIRE_CONFIRM_MS)
            if (connectivity.isOnline) mark(companyId, p, loggedIn = false)
        }
    }

    private fun cancelPendingExpire() {
        pendingExpire?.cancel()
        pendingExpire = null
    }

    private fun mark(companyId: Long, p: Portal, loggedIn: Boolean) {
        optimisticStatus = if (loggedIn) PortalConnectionStatus.CONECTADO else PortalConnectionStatus.SESSAO_EXPIRADA
        optimisticAt = System.currentTimeMillis()
        viewModelScope.launch {
            runCatching { portals.markSessionDetected(companyId, p, loggedIn) }
                .onSuccess { _events.send(if (loggedIn) "Sessão aberta em ${p.displayName}" else "${p.displayName}: sessão expirada — faça login novamente") }
        }
    }

    /** Switch "Manter sessão ativa" da barra (auditado). */
    fun setKeepAlive(enabled: Boolean) {
        val p = portal ?: return
        val companyId = state.value.companyId ?: return
        viewModelScope.launch {
            runCatching { keepAlive.setEnabled(companyId, p, enabled) }
                .onSuccess {
                    _events.send(
                        if (enabled) "Manter sessão ativa ligado: recarrega sua página a cada ${state.value.keepAliveMinutes} min"
                        else "Manter sessão ativa desligado",
                    )
                }
                .onFailure { _events.send(it.message ?: "Não foi possível alterar a preferência") }
        }
    }

    /** "Sair do portal": expira os cookies do portal no perfil da empresa, apaga a última página e marca DESCONECTADO. */
    fun signOut(onDone: () -> Unit = {}) {
        val p = portal ?: return
        val companyId = state.value.companyId ?: return
        if (busy.value || !state.value.canSignOut) return
        busy.value = true
        viewModelScope.launch {
            val result = runCatching {
                PortalWebSessions.clearPortalCookies(companyId, p, visitedHosts.toList())
                portals.clearWebSession(companyId, p) // também apaga a última URL; o keep-alive para (status ≠ CONECTADO)
            }
            lastUrl = null; lastWasLogin = null; lastPrevWasLogin = null; lastSavedResumeUrl = null
            optimisticStatus = null
            busy.value = false
            result.onSuccess { _events.send("Você saiu de ${p.displayName}"); onDone() }
                .onFailure { _events.send(it.message ?: "Não foi possível encerrar a sessão") }
        }
    }

    fun flush() {
        state.value.companyId?.let { PortalWebSessions.flush(it) }
    }

    private companion object {
        const val OPTIMISTIC_WINDOW_MS = 15_000L
        const val EXPIRE_CONFIRM_MS = 3_000L
    }
}
