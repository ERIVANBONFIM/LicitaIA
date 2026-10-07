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
    /** O portal tem o fluxo de "Entrar automaticamente com certificado digital" mapeado (hoje só Compras.gov.br). */
    val autoLoginSupported: Boolean = false,
    /** "Entrar automaticamente com certificado digital" ligado para este portal/empresa. */
    val autoLoginOn: Boolean = false,
    /** CNPJ da empresa ativa (usado só para escolher a linha certa na seleção de empresa do portal). */
    val companyCnpj: String = "",
)

/** Aviso do login automático na tela do portal. */
sealed interface AutoLoginBanner {
    data object Running : AutoLoginBanner
    /** Parou e devolveu o controle: "Conclua o login no portal". */
    data class NeedsUser(val reason: String) : AutoLoginBanner
}

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
    /** WebView retido por empresa/portal (sobrevive ao sair/voltar da tela; usado também pelo keep-alive). */
    val webViews: PortalWebViewHolder,
    /** Robôs do Comprasnet: banner "robô parado/aguardando você" sobre a página e "Mapear esta tela". */
    val robots: com.licitaia.feature.live.automation.PortalRobotEngine,
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
                    autoLoginSupported = CertAutoLogin.supports(p),
                    autoLoginOn = CertAutoLogin.supports(p) && st.isAutoCertLoginOn(companyId, p),
                    companyCnpj = session.activeCompany.cnpj,
                )
            }.catch { emit(PortalWebUiState(portal = p, companyId = companyId, ready = true, online = connectivity.isOnline)) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PortalWebUiState(portal = portal))

    /** "Compras.gov.br instável" (503 do portal) para esta empresa/portal; null = sem instabilidade. */
    val unstable: StateFlow<PortalInstability.State?> = auth.session.flatMapLatest { session ->
        val p = portal
        if (session == null || p == null) flowOf(null) else keepAlive.unstableState(session.activeCompany.id, p)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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
    fun onNavigated(
        url: String,
        hasCookies: Boolean,
        contentExpired: Boolean = false,
        loadFailed: Boolean = false,
        /** Entrada oficial (inicial ou reentrada) em andamento: a página de login dela não é "sessão caiu". */
        entering: Boolean = false,
    ): PortalWebPolicy.Signal {
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
        val evaluated = PortalWebPolicy.evaluate(p, url, hasCookies, prevWasLogin, status, contentExpired, online = online, loadFailed = loadFailed)
        // Durante a entrada/reentrada quem conclui expirado é o EntryGate (só se o login do gov.br não for superado).
        val signal = if (entering && evaluated == PortalWebPolicy.Signal.EXPIRED && isLogin) PortalWebPolicy.Signal.NONE else evaluated
        // "Verificando sessão…": a primeira página conclusiva decide (área logada → aberta; gov.br público → login).
        if (signal != PortalWebPolicy.Signal.BLOCKED) {
            _sessionCheck.value = PortalWebPolicy.resolveSessionCheck(p, _sessionCheck.value, url, signal, contentExpired)
        }
        if (signal != PortalWebPolicy.Signal.BLOCKED) {
            lastPrevWasLogin = prevWasLogin
            lastUrl = url
            // Login (ou página de passagem do Comprasnet logo após o login) vale como "anterior era login".
            lastWasLogin = PortalWebPolicy.carriesLoginFlag(p, url, prevWasLogin) || isLogin
            PortalWebPolicy.host(url)?.let { visitedHosts += "https://$it/" }
        }
        when (signal) {
            PortalWebPolicy.Signal.CONNECTED -> { cancelPendingExpire(); mark(companyId, p, loggedIn = true) }
            PortalWebPolicy.Signal.EXPIRED -> scheduleExpire(companyId, p)
            else -> Unit
        }
        // Status já era CONECTADO (sem novo CONNECTED) e a aba chegou à área logada: a instabilidade também acabou.
        if (signal == PortalWebPolicy.Signal.NONE && unstable.value != null && !contentExpired &&
            status == PortalConnectionStatus.CONECTADO && PortalWebPolicy.isLoggedArea(p, url)
        ) {
            keepAlive.onPortalConnected(companyId, p)
        }
        // "Voltar para onde estava": guarda a página da área logada (sanitizada), nunca com aviso de sessão encerrada.
        val loggedNow = signal == PortalWebPolicy.Signal.CONNECTED ||
            (signal == PortalWebPolicy.Signal.NONE && status == PortalConnectionStatus.CONECTADO && !contentExpired)
        if (loggedNow) rememberResumeUrl(companyId, p, url)
        return signal
    }

    /** Sessão aberta agora (status persistido ou recém-sinalizado nesta aba). */
    fun sessionOpen(): Boolean = effectiveStatus(state.value.status) == PortalConnectionStatus.CONECTADO

    private val _sessionCheck = MutableStateFlow(PortalWebPolicy.SessionCheck.NONE)
    /** Selo "Verificando sessão…" / "Faça login no portal" enquanto a aba sem estado confirma o status persistido. */
    val sessionCheck: StateFlow<PortalWebPolicy.SessionCheck> = _sessionCheck

    /** A tela abriu um WebView sem estado: com status CONECTADO, o selo fica "Verificando sessão…" até a 1ª página conclusiva. */
    fun beginSessionCheck(freshTab: Boolean): Boolean {
        val p = portal ?: return false
        val check = PortalWebPolicy.initialSessionCheck(p, effectiveStatus(state.value.status), freshTab)
        if (check == PortalWebPolicy.SessionCheck.VERIFYING) _sessionCheck.value = check
        return check == PortalWebPolicy.SessionCheck.VERIFYING
    }

    fun isVerifyingSession(): Boolean = _sessionCheck.value == PortalWebPolicy.SessionCheck.VERIFYING

    /**
     * A aba caiu no www.gov.br público vindo do portal (ou com a sessão marcada como aberta): NÃO está logada. Marca
     * SESSAO_EXPIRADA na hora (página carregada com rede; não depende de confirmação) se estava CONECTADO. Logo após
     * abrir ("Verificando sessão…") o aviso é "Faça login no portal", não "sessão expirada". O relogin automático
     * NÃO é disparado daqui: a tela navega para a entrada oficial e o EntryGate inicia o login automático nela.
     */
    fun onLoginRequired() {
        val p = portal ?: return
        val s = state.value
        val companyId = s.companyId ?: return
        cancelPendingExpire()
        reloginTried = true
        if (_sessionCheck.value == PortalWebPolicy.SessionCheck.VERIFYING) _sessionCheck.value = PortalWebPolicy.SessionCheck.LOGIN_REQUIRED
        if (effectiveStatus(s.status) != PortalConnectionStatus.CONECTADO) return
        mark(companyId, p, loggedIn = false)
    }

    /** Aviso curto na tela (snackbar). */
    fun notify(message: String) {
        viewModelScope.launch { _events.send(message) }
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

    /**
     * "Não autorizado" no cnetmobile: a tela volta UMA vez à área de trabalho (intro.htm) e reabre "Licitação e Dispensa
     * (novo)" pelo link do portal. Não marca expirado; avisa
     * "Reconectando ao …" e descarta qualquer EXPIRED pendente.
     */
    fun onReconnecting() {
        val p = portal ?: return
        cancelPendingExpire()
        viewModelScope.launch { _events.send("Reconectando ao ${p.displayName}…") }
    }

    /**
     * A entrada/reentrada terminou no login do gov.br sem chegar à área logada (e o login automático está desligado ou
     * já parou): marca SESSAO_EXPIRADA (com o alerta) se estava CONECTADO. Não dispara outro relogin automático.
     */
    fun onEntryFailed() {
        val p = portal ?: return
        val s = state.value
        val companyId = s.companyId ?: return
        reloginTried = true
        if (_sessionCheck.value == PortalWebPolicy.SessionCheck.VERIFYING) _sessionCheck.value = PortalWebPolicy.SessionCheck.LOGIN_REQUIRED
        if (effectiveStatus(s.status) == PortalConnectionStatus.CONECTADO) scheduleExpire(companyId, p)
    }

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
        // Logo após abrir (a verificação concluiu "sem sessão"): o aviso é "Faça login no portal", não "sessão expirada".
        val justOpened = _sessionCheck.value != PortalWebPolicy.SessionCheck.NONE
        if (loggedIn) {
            reloginTried = false
            _sessionCheck.value = PortalWebPolicy.SessionCheck.NONE
            if (_autoLoginBanner.value is AutoLoginBanner.NeedsUser) _autoLoginBanner.value = null
            // Chegou à área logada: encerra o "portal instável" (e avisa "voltou — você está conectado", se havia).
            keepAlive.onPortalConnected(companyId, p)
        }
        viewModelScope.launch {
            runCatching { portals.markSessionDetected(companyId, p, loggedIn) }
                .onSuccess {
                    _events.send(
                        when {
                            loggedIn -> "Sessão aberta em ${p.displayName}"
                            justOpened -> "${p.displayName}: faça login no portal"
                            else -> "${p.displayName}: sessão expirada — faça login novamente"
                        },
                    )
                }
            // Sessão caiu com a tela aberta e o login automático ligado: UMA tentativa por evento de reconexão.
            if (!loggedIn && state.value.autoLoginOn && !reloginTried) {
                reloginTried = true
                _autoReloginRequests.send(Unit)
            }
        }
    }

    // ------------------------------------------------------------------ login automático com certificado

    private val _autoLoginBanner = MutableStateFlow<AutoLoginBanner?>(null)
    val autoLoginBanner: StateFlow<AutoLoginBanner?> = _autoLoginBanner

    /** Pedidos de relogin automático (sessão expirou com a tela aberta). A tela inicia no WebView retido. */
    private val _autoReloginRequests = Channel<Unit>(Channel.CONFLATED)
    val autoReloginRequests = _autoReloginRequests.receiveAsFlow()
    private var reloginTried = false

    fun onAutoLoginStarted() {
        _autoLoginBanner.value = AutoLoginBanner.Running
    }

    fun dismissAutoLoginBanner() {
        _autoLoginBanner.value = null
    }

    /** Resultado de uma tentativa iniciada pela tela: aviso + auditoria CONEXAO_PORTAL (sem URLs). */
    fun onAutoLoginOutcome(outcome: CertAutoLogin.Outcome) {
        val p = portal ?: return
        val companyId = state.value.companyId ?: return
        _autoLoginBanner.value = when {
            // 503 do portal: o aviso "Compras.gov.br instável" (com "Tentar agora") substitui o "Conclua o login".
            outcome is CertAutoLogin.Outcome.Stopped && outcome.reason == CertAutoLogin.StopReason.PORTAL_UNSTABLE -> null
            outcome is CertAutoLogin.Outcome.Stopped -> AutoLoginBanner.NeedsUser(outcome.reason.userText)
            else -> null
        }
        val details = when (outcome) {
            CertAutoLogin.Outcome.Success -> "Login automático com certificado: sucesso"
            CertAutoLogin.Outcome.NotNeeded -> return
            is CertAutoLogin.Outcome.Stopped -> "Login automático com certificado: ${outcome.reason.auditText}"
        }
        viewModelScope.launch { runCatching { portals.auditKeepAlive(companyId, p, details) } }
    }

    /** Switch "Entrar automaticamente com certificado digital" (menu ⋮; auditado). */
    fun setAutoCertLogin(enabled: Boolean) {
        val p = portal ?: return
        val companyId = state.value.companyId ?: return
        viewModelScope.launch {
            runCatching { keepAlive.setAutoCertLogin(companyId, p, enabled) }
                .onSuccess {
                    _events.send(
                        if (enabled) "Login automático com certificado ligado: o app só clica nas etapas do login; CAPTCHA e códigos ficam com você."
                        else "Login automático com certificado desligado",
                    )
                }
                .onFailure { _events.send(it.message ?: "Não foi possível alterar a preferência") }
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
            // O WebView retido guarda o token da SPA em sessionStorage: descarta-o (a tela cria um novo).
            runCatching { webViews.discard(companyId, p) }
            // Saiu do portal: para as novas tentativas do "portal instável".
            runCatching { keepAlive.clearUnstable(companyId, p) }
            _autoLoginBanner.value = null
            reloginTried = true // saiu de propósito: nada de relogin automático nesta tela
            lastUrl = null; lastWasLogin = null; lastPrevWasLogin = null; lastSavedResumeUrl = null
            optimisticStatus = null
            _sessionCheck.value = PortalWebPolicy.SessionCheck.NONE
            busy.value = false
            result.onSuccess { _events.send("Você saiu de ${p.displayName}"); onDone() }
                .onFailure { _events.send(it.message ?: "Não foi possível encerrar a sessão") }
        }
    }

    /**
     * "Trocar certificado": esquece o certificado digital (alias do KeyChain) lembrado para os hosts deste portal;
     * a próxima exigência do site abre o seletor do Android. Nenhuma chave é exportada ou copiada.
     */
    fun forgetCertificate(onDone: () -> Unit = {}) {
        val p = portal ?: return
        val companyId = state.value.companyId ?: return
        viewModelScope.launch {
            runCatching { webViews.forgetClientCertificate(companyId, p) }
                .onSuccess { _events.send("Certificado esquecido: o Android vai perguntar qual usar no próximo acesso com certificado."); onDone() }
                .onFailure { _events.send(it.message ?: "Não foi possível trocar o certificado") }
        }
    }

    fun flush() {
        state.value.companyId?.let { PortalWebSessions.flush(it) }
    }

    private companion object {
        const val OPTIMISTIC_WINDOW_MS = 15_000L
        const val EXPIRE_CONFIRM_MS = 3_000L
    }

    /** "Mapear esta tela": snapshot estrutural da página atual da aba retida (modo mapear do robô). */
    fun mapScreen(companyId: Long, onDone: (Boolean) -> Unit) {
        viewModelScope.launch { onDone(robots.mapCurrentScreen(companyId)) }
    }
}