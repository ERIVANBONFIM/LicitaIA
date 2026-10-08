package com.licitaia.app.shell

import androidx.biometric.BiometricPrompt
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.licitaia.core.platform.PlatformRepository
import com.licitaia.core.platform.net.UserDto
import com.licitaia.core.platform.session.PlatformSession
import com.licitaia.core.ui.nav.Routes
import com.licitaia.core.ui.nav.ShellState
import com.licitaia.domain.auth.IdentitySignOut
import com.licitaia.domain.auth.PinVerification
import com.licitaia.domain.live.LiveSessionManager
import com.licitaia.domain.model.AppNotification
import com.licitaia.domain.model.AppSettings
import com.licitaia.domain.model.AuthSession
import com.licitaia.domain.model.Company
import com.licitaia.domain.model.Segment
import com.licitaia.domain.model.UserProfile
import com.licitaia.domain.model.UserRole
import com.licitaia.domain.repository.AppNotifier
import com.licitaia.domain.repository.AuthRepository
import com.licitaia.domain.repository.CompanyRepository
import com.licitaia.domain.repository.NotificationRepository
import com.licitaia.domain.repository.SettingsRepository
import com.licitaia.domain.network.ConnectivityMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Estado global do app: sessão, empresa ativa, sino, bloqueio e alertas in-app. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ShellViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    companyRepository: CompanyRepository,
    notificationRepository: NotificationRepository,
    private val liveSessionManager: LiveSessionManager,
    settingsRepository: SettingsRepository,
    appNotifier: AppNotifier,
    private val identitySignOut: IdentitySignOut,
    private val platformRepository: PlatformRepository,
    connectivity: ConnectivityMonitor,
) : ViewModel() {

    /** Conexão com a internet (rede validada) — faixa global "Sem internet" e aviso de reconexão. */
    val online: StateFlow<Boolean> = connectivity.online

    /**
     * Rota inicial do app (null = ainda restaurando). Opção A — login único da plataforma:
     * a porta de entrada padrão é o login da plataforma. Ordem de prioridade na abertura:
     *  1) sessão de plataforma salva → abre direto no modo plataforma ([Routes.PLATFORM_TENDERS]);
     *  2) sessão local lembrada → mantém o comportamento local atual ([Routes.DASHBOARD]);
     *  3) sem nenhuma sessão → login da plataforma ([Routes.PLATFORM_LOGIN]).
     * O modo local e a demonstração continuam acessíveis pelo link discreto no rodapé do login da plataforma.
     */
    private val _startRoute = MutableStateFlow<String?>(null)
    val startRoute: StateFlow<String?> = _startRoute.asStateFlow()

    val session: StateFlow<AuthSession?> = authRepository.session

    /** Sessão da plataforma (VPS). */
    val platformSession: StateFlow<PlatformSession> = platformRepository.session

    /**
     * true = app rodando em MODO PLATAFORMA (sessão da VPS, sem sessão local). Decide a fonte de dados e o
     * roteamento dos menus para as telas da plataforma.
     */
    val platformMode: StateFlow<Boolean> =
        combine(session, platformSession) { local, plat ->
            local == null && plat is PlatformSession.SignedIn
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Sessão que alimenta o SHELL (dashboard, barra, menu). No modo local é a sessão local; no modo plataforma
     * é uma sessão sintética derivada do usuário/empresa da VPS, para reaproveitar o mesmo shell e telas.
     */
    val shellSession: StateFlow<AuthSession?> =
        combine(session, platformSession) { local, plat ->
            local ?: (plat as? PlatformSession.SignedIn)?.let { syntheticSession(it.user) }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val settings: StateFlow<AppSettings> =
        settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val alerts: SharedFlow<AppNotification> = appNotifier.inAppAlerts

    private val unread = session.flatMapLatest { s ->
        if (s == null) flowOf(0) else notificationRepository.observeUnreadCount(s.activeCompany.id)
    }

    val shellState: StateFlow<ShellState> =
        combine(session, unread, liveSessionManager.sessions) { s, unreadCount, live ->
            ShellState(
                companyName = s?.activeCompany?.tradeName.orEmpty(),
                userName = s?.user?.name.orEmpty(),
                unreadNotifications = unreadCount,
                criticalPending = s != null && live.any { it.captchaPending },
                demo = s != null && (s.user.demo || s.activeCompany.demo),
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, ShellState())

    /** Empresas às quais o usuário logado tem acesso (troca rápida no menu). */
    val companies: StateFlow<List<Company>> =
        combine(session, companyRepository.observeCompanies()) { s, all ->
            if (s == null) emptyList() else all.filter { it.id in s.user.companyIds }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val liveCount: StateFlow<Int> = liveSessionManager.sessions
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    private val _locked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()
    private val _hasPin = MutableStateFlow(false)
    val hasPin: StateFlow<Boolean> = _hasPin.asStateFlow()
    private var backgroundAt: Long? = null

    init {
        viewModelScope.launch {
            val restored = runCatching { authRepository.restoreSession() }.getOrNull()
            // Sessão de plataforma salva tem prioridade: abre direto no modo plataforma.
            val platformSignedIn = runCatching {
                platformRepository.ensureSessionLoaded()
                platformRepository.session.value is PlatformSession.SignedIn
            }.getOrDefault(false)
            // Só aplica o bloqueio local quando a abertura será efetivamente no modo local (dashboard).
            if (restored != null && !platformSignedIn) {
                // Abertura a frio com sessão lembrada: exige desbloqueio se houver proteção configurada.
                val s = settingsRepository.settings.first()
                _hasPin.value = runCatching { authRepository.hasPin() }.getOrDefault(false)
                if (s.biometricLock || _hasPin.value) _locked.value = true
            }
            val target = when {
                // Sessão de plataforma → abre o SHELL completo (dashboard/menu/barra) em modo plataforma.
                platformSignedIn -> Routes.DASHBOARD
                restored != null -> Routes.DASHBOARD
                // Sem sessão: tela de login unificada (abre com o modo PLATAFORMA já selecionado).
                else -> Routes.LOGIN
            }
            // Diagnóstico de abertura (confirmar no aparelho: `adb logcat -s LicitaStart`).
            android.util.Log.i(
                "LicitaStart",
                "startRoute=$target (plataforma=$platformSignedIn, localLembrada=${restored != null})",
            )
            _startRoute.value = target
        }
        // Login / troca de empresa → restaura (ou cria) as sessões de pregão da empresa ativa.
        viewModelScope.launch {
            session.map { it?.activeCompany?.id }.distinctUntilChanged().collect { companyId ->
                if (companyId != null) runCatching { liveSessionManager.restoreOrSeed(companyId) }
                else _locked.value = false
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            // Modo plataforma: encerra a sessão da VPS (não há sessão local para limpar).
            if (session.value == null && platformSession.value is PlatformSession.SignedIn) {
                runCatching { platformRepository.logout() }
                return@launch
            }
            val provider = session.value?.user?.provider
            authRepository.logout()
            // Conta Google: limpa o estado de credencial para que o próximo login mostre o seletor de contas.
            if (provider != null) runCatching { identitySignOut.signOut(provider) }
        }
    }

    /** Sessão sintética (somente para o shell) a partir do usuário/empresa da plataforma. */
    private fun syntheticSession(user: UserDto): AuthSession {
        val role = when (user.role.lowercase()) {
            "admin" -> UserRole.ADMIN
            "diretoria" -> UserRole.DIRETORIA
            "financeiro" -> UserRole.FINANCEIRO
            "tecnico", "viewer" -> UserRole.TECNICO
            else -> UserRole.LICITACOES
        }
        val company = Company(
            id = PLATFORM_COMPANY_ID,
            name = user.empresa?.razaoSocial?.ifBlank { "Minha empresa" } ?: "Minha empresa",
            tradeName = user.empresa?.razaoSocial?.ifBlank { "Minha empresa" } ?: "Minha empresa",
            cnpj = user.empresa?.cnpj.orEmpty(),
            segment = Segment.PERSONALIZADO,
            uf = "",
            city = "",
        )
        return AuthSession(
            user = UserProfile(
                id = PLATFORM_USER_ID,
                name = user.nome.ifBlank { user.email },
                email = user.email,
                role = role,
                companyIds = listOf(PLATFORM_COMPANY_ID),
            ),
            activeCompany = company,
        )
    }

    fun switchCompany(companyId: Long, onResult: (Boolean) -> Unit) {
        viewModelScope.launch { onResult(authRepository.switchCompany(companyId).isSuccess) }
    }

    /** true quando a sessão atual é a demonstração isolada. */
    val isDemo: Boolean get() = session.value?.user?.demo == true

    /** "Sair da demonstração": apaga a empresa demo, seus dados e o usuário demo; volta ao login. */
    fun exitDemo(onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            if (!isDemo) { onResult(Result.failure(IllegalStateException("Sessão não é de demonstração."))); return@launch }
            val result = runCatching { authRepository.exitDemo().getOrThrow() }
            onResult(result)
        }
    }

    /** "Reiniciar demonstração": recria o espaço demo com os dados de exemplo originais. */
    fun resetDemo(onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            if (!isDemo) { onResult(Result.failure(IllegalStateException("Sessão não é de demonstração."))); return@launch }
            val result = runCatching { authRepository.resetDemo().getOrThrow() }.map { }
            onResult(result)
        }
    }

    fun onBackground() {
        backgroundAt = System.currentTimeMillis()
    }

    fun onForeground() {
        val since = backgroundAt ?: return
        backgroundAt = null
        if (session.value == null || _locked.value) return
        viewModelScope.launch {
            _hasPin.value = runCatching { authRepository.hasPin() }.getOrDefault(false)
            val s = settings.value
            val protectionOn = s.biometricLock || _hasPin.value
            val timedOut = System.currentTimeMillis() - since >= s.sessionTimeoutMinutes * 60_000L
            if (protectionOn && timedOut) _locked.value = true
        }
    }

    /**
     * Desbloqueio por PIN. O repositório aplica o bloqueio progressivo (5 erros → 30 s, 1 min, 5 min…),
     * persistido no cofre, e audita falhas/bloqueios; aqui só se reflete o resultado na UI.
     */
    fun unlockWithPin(pin: String, onResult: (PinVerification) -> Unit) {
        viewModelScope.launch {
            val outcome = runCatching { authRepository.verifyPinDetailed(pin) }
                .getOrElse { PinVerification.Wrong(remainingAttempts = 0) }
            if (outcome is PinVerification.Success) _locked.value = false
            onResult(outcome)
        }
    }

    /**
     * Desbloqueio por biometria/credencial do aparelho. Exige o [BiometricPrompt.AuthenticationResult]
     * entregue pelo callback `onAuthenticationSucceeded`: não há caminho para desbloquear sem ele.
     */
    @Suppress("UNUSED_PARAMETER")
    fun unlock(result: BiometricPrompt.AuthenticationResult) {
        // O parâmetro não nulo é a prova: só o BiometricPrompt constrói um AuthenticationResult.
        _locked.value = false
    }

    private companion object {
        // IDs sentinela da sessão sintética de plataforma (negativos: nunca colidem com IDs locais).
        const val PLATFORM_USER_ID = -1000L
        const val PLATFORM_COMPANY_ID = -1000L
    }
}
