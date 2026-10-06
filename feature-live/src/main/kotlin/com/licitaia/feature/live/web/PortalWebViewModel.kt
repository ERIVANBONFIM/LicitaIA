package com.licitaia.feature.live.web

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.PortalConnectionStatus
import com.licitaia.domain.repository.AuthRepository
import com.licitaia.domain.repository.PortalRepository
import com.licitaia.domain.security.Permission
import com.licitaia.domain.security.Rbac
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
)

/**
 * Estado da sessão do portal no navegador interno. Recebe apenas URLs navegadas e o fato
 * de existirem cookies — nunca conteúdo de página nem credenciais.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PortalWebViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val auth: AuthRepository,
    private val portals: PortalRepository,
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

    val state: StateFlow<PortalWebUiState> = auth.session.flatMapLatest { session ->
        val p = portal
        if (session == null || p == null) {
            flowOf(PortalWebUiState(portal = p, companyId = null, ready = true))
        } else {
            val companyId = session.activeCompany.id
            val canSignOut = Rbac.can(session.user.role, Permission.OPERAR_SESSOES) || Rbac.can(session.user.role, Permission.GERENCIAR_EMPRESAS)
            combine(portals.observeSessions(companyId), busy) { sessions, b ->
                val ps = sessions.firstOrNull { it.portal == p }
                PortalWebUiState(
                    portal = p, companyId = companyId,
                    status = ps?.status ?: PortalConnectionStatus.DESCONECTADO,
                    lastLoginAt = ps?.lastLoginAt,
                    requiresLogin = PortalWebPolicy.rules(p).requiresLogin,
                    canSignOut = canSignOut, busy = b, ready = true,
                )
            }.catch { emit(PortalWebUiState(portal = p, companyId = companyId, ready = true)) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PortalWebUiState(portal = portal))

    /**
     * Chamado pela WebView em doUpdateVisitedHistory/onPageFinished.
     * @return BLOCKED quando a URL está fora da allowlist (a tela decide bloquear).
     */
    fun onNavigated(url: String, hasCookies: Boolean): PortalWebPolicy.Signal {
        val p = portal ?: return PortalWebPolicy.Signal.NONE
        val s = state.value
        val companyId = s.companyId ?: return PortalWebPolicy.Signal.NONE
        val isLogin = PortalWebPolicy.isLoginPage(p, url)
        // Mesma URL avaliada duas vezes (history + finished): reaproveita o "anterior" da primeira avaliação.
        val prevWasLogin = if (url == lastUrl) lastPrevWasLogin else lastWasLogin
        val signal = PortalWebPolicy.evaluate(p, url, hasCookies, prevWasLogin, s.status)
        if (signal != PortalWebPolicy.Signal.BLOCKED) {
            lastPrevWasLogin = prevWasLogin
            lastUrl = url
            lastWasLogin = isLogin
            PortalWebPolicy.host(url)?.let { visitedHosts += "https://$it/" }
        }
        when (signal) {
            PortalWebPolicy.Signal.CONNECTED -> mark(companyId, p, loggedIn = true)
            PortalWebPolicy.Signal.EXPIRED -> mark(companyId, p, loggedIn = false)
            else -> Unit
        }
        return signal
    }

    private fun mark(companyId: Long, p: Portal, loggedIn: Boolean) {
        viewModelScope.launch {
            runCatching { portals.markSessionDetected(companyId, p, loggedIn) }
                .onSuccess { _events.send(if (loggedIn) "Sessão aberta em ${p.displayName}" else "${p.displayName}: sessão expirada — faça login novamente") }
        }
    }

    /** "Sair do portal": expira os cookies do portal no perfil da empresa e marca DESCONECTADO. */
    fun signOut(onDone: () -> Unit = {}) {
        val p = portal ?: return
        val companyId = state.value.companyId ?: return
        if (busy.value || !state.value.canSignOut) return
        busy.value = true
        viewModelScope.launch {
            val result = runCatching {
                PortalWebSessions.clearPortalCookies(companyId, p, visitedHosts.toList())
                portals.clearWebSession(companyId, p)
            }
            lastUrl = null; lastWasLogin = null; lastPrevWasLogin = null
            busy.value = false
            result.onSuccess { _events.send("Você saiu de ${p.displayName}"); onDone() }
                .onFailure { _events.send(it.message ?: "Não foi possível encerrar a sessão") }
        }
    }

    fun flush() {
        state.value.companyId?.let { PortalWebSessions.flush(it) }
    }
}
