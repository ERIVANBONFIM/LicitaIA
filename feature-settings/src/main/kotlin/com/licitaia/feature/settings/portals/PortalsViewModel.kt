package com.licitaia.feature.settings.portals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.licitaia.domain.model.AppSettings
import com.licitaia.domain.model.ConnectorCapabilities
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.PortalConnectionStatus
import com.licitaia.domain.model.PortalSession
import com.licitaia.domain.repository.AuthRepository
import com.licitaia.domain.repository.PortalRepository
import com.licitaia.domain.repository.SettingsRepository
import com.licitaia.feature.live.keepalive.PortalKeepAliveController
import com.licitaia.domain.security.Permission
import com.licitaia.domain.security.Rbac
import com.licitaia.feature.live.web.PortalWebPolicy
import com.licitaia.feature.live.web.PortalWebSessions
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
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PortalRow(
    val portal: Portal,
    val session: PortalSession?,
    /** Capacidades declaradas pelo conector via [PortalRepository.capabilities] (null = indisponível). */
    val capabilities: ConnectorCapabilities?,
    /** "Manter sessão ativa" ligado para este portal na empresa ativa. */
    val keepAliveOn: Boolean = false,
) {
    /** false = portal público (PNCP): só "Abrir". */
    val requiresLogin: Boolean get() = PortalWebPolicy.rules(portal).requiresLogin
    val status: PortalConnectionStatus get() = session?.status ?: PortalConnectionStatus.DESCONECTADO
    val startUrl: String get() = PortalWebPolicy.startUrl(portal)

    /** true quando o conector expõe API pública/oficial de consulta (hoje: PNCP). */
    val hasPublicApi: Boolean get() = capabilities?.supportsOfficialApi == true

    /** Texto de acesso derivado das capacidades reais do conector, nunca de texto fixo por portal. */
    val accessLabel: String get() = when {
        capabilities == null -> "Capacidades do conector indisponíveis"
        capabilities.isMock -> "Conector de simulação · sem acesso real"
        capabilities.supportsOfficialApi && !capabilities.supportsPersistentSession -> "API pública (consulta) · sem login"
        capabilities.supportsOfficialApi -> "API oficial · login no portal"
        capabilities.supportsBrowserAutomation -> "Automação de navegador autorizada"
        else -> "Acesso manual no navegador interno · sem API autorizada"
    }

    /** Observações do conector (MFA/CAPTCHA) para a linha de detalhes. */
    val accessNotes: List<String> get() = buildList {
        val c = capabilities ?: return@buildList
        if (c.requiresMfa) add("MFA do portal resolvido por você")
        if (c.mayShowCaptcha) add("pode exibir CAPTCHA")
    }
}

data class PortalsUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val noSession: Boolean = false,
    val canManage: Boolean = false,
    val roleLabel: String = "",
    val companyName: String = "",
    val rows: List<PortalRow> = emptyList(),
    /** Portal aguardando confirmação de "Sair do portal". */
    val confirmSignOut: Portal? = null,
    val signingOut: Set<Portal> = emptySet(),
    /** false = o WebView do aparelho não separa cookies por empresa (perfil compartilhado). */
    val isolatedProfiles: Boolean = true,
    /** Intervalo do "Manter sessão ativa" (min). */
    val keepAliveMinutes: Int = AppSettings().portalKeepAliveMinutes,
) {
    val connectedCount get() = rows.count { it.status == PortalConnectionStatus.CONECTADO }
}

/**
 * Tela "Portais oficiais": o login é feito pelo usuário no navegador interno
 * (PortalWebViewScreen); o app NÃO coleta usuário/senha. Aqui só se vê o status
 * detectado e se encerra a sessão (cookies).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PortalsViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val portals: PortalRepository,
    private val settings: SettingsRepository,
    private val keepAlive: PortalKeepAliveController,
) : ViewModel() {

    init {
        keepAlive.start()
    }

    private val local = MutableStateFlow(PortalsUiState(isolatedProfiles = PortalWebSessions.supportsProfiles()))
    private val retry = MutableStateFlow(0)
    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    val state: StateFlow<PortalsUiState> = combine(auth.session, retry) { s, _ -> s }
        .flatMapLatest { session ->
            if (session == null) {
                flowOf(PortalsUiState(loading = false, noSession = true))
            } else {
                val caps = Portal.entries.associateWith { p -> runCatching { portals.capabilities(p) }.getOrNull() }
                val companyId = session.activeCompany.id
                combine(portals.observeSessions(companyId), local, settings.settings) { sessions, l, st ->
                    l.copy(
                        loading = false, error = null,
                        // Encerrar sessão do portal: operação de sessão ou gestão da empresa.
                        canManage = Rbac.can(session.user.role, Permission.OPERAR_SESSOES) || Rbac.can(session.user.role, Permission.GERENCIAR_EMPRESAS),
                        roleLabel = session.user.role.label,
                        companyName = session.activeCompany.tradeName.ifBlank { session.activeCompany.name },
                        rows = Portal.entries.map { p ->
                            PortalRow(p, sessions.firstOrNull { it.portal == p }, caps[p], keepAliveOn = st.isPortalKeepAliveOn(companyId, p))
                        },
                        keepAliveMinutes = st.portalKeepAliveMinutes,
                    )
                }.catch { emit(PortalsUiState(loading = false, error = it.message ?: "Falha ao carregar os portais.")) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PortalsUiState())

    fun retry() = retry.update { it + 1 }

    /** "Manter sessão ativa" do card (auditado ao ligar/desligar). */
    fun setKeepAlive(portal: Portal, enabled: Boolean) {
        val session = auth.session.value ?: return
        viewModelScope.launch {
            runCatching { keepAlive.setEnabled(session.activeCompany.id, portal, enabled) }
                .onSuccess { _events.send(if (enabled) "${portal.displayName}: manter sessão ativa ligado" else "${portal.displayName}: manter sessão ativa desligado") }
                .onFailure { _events.send(it.message ?: "Não foi possível alterar a preferência") }
        }
    }

    fun setKeepAliveMinutes(minutes: Int) {
        viewModelScope.launch { runCatching { keepAlive.setIntervalMinutes(minutes) } }
    }

    fun askSignOut(portal: Portal) {
        if (!state.value.canManage) return
        local.update { it.copy(confirmSignOut = portal) }
    }

    fun dismissSignOut() = local.update { it.copy(confirmSignOut = null) }

    /** "Sair do portal": expira os cookies do portal no perfil da empresa e marca DESCONECTADO (auditado). */
    fun signOut(portal: Portal) {
        val session = auth.session.value ?: return
        if (!state.value.canManage || portal in state.value.signingOut) return
        local.update { it.copy(confirmSignOut = null, signingOut = it.signingOut + portal) }
        viewModelScope.launch {
            runCatching {
                PortalWebSessions.clearPortalCookies(session.activeCompany.id, portal)
                portals.clearWebSession(session.activeCompany.id, portal)
            }
                .onSuccess { _events.send("Você saiu de ${portal.displayName}") }
                .onFailure { _events.send(it.message ?: "Não foi possível encerrar a sessão") }
            local.update { it.copy(signingOut = it.signingOut - portal) }
        }
    }
}
