package com.licitaia.app.shell

import androidx.biometric.BiometricPrompt
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.licitaia.core.data.session.SessionHolder
import com.licitaia.core.platform.PlatformRepository
import com.licitaia.core.platform.session.PlatformIdentity
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
    private val sessionHolder: SessionHolder,
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

    /**
     * Sessão corrente (fonte única [SessionHolder]). No modo plataforma ela é uma sessão LOCAL SINTÉTICA
     * derivada do usuário/empresa da VPS (identidade estável), gravada no holder para que o MESMO shell e as
     * telas locais (Segurança, IA, Portais, Configurações) operem com a identidade da plataforma.
     */
    val session: StateFlow<AuthSession?> = authRepository.session

    /** Sessão da plataforma (VPS). */
    val platformSession: StateFlow<PlatformSession> = platformRepository.session

    /** true = app rodando em MODO PLATAFORMA (sessão sintética ativa). Decide o roteamento dos menus. */
    private val _platformMode = MutableStateFlow(false)
    val platformMode: StateFlow<Boolean> = _platformMode.asStateFlow()

    /** Sessão que alimenta o shell (= a sessão corrente do holder; sintética no modo plataforma). */
    val shellSession: StateFlow<AuthSession?> get() = session

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
            // Sessão de plataforma salva tem prioridade quando não há sessão local lembrada.
            val platformSignedIn = runCatching {
                platformRepository.ensureSessionLoaded()
                platformRepository.session.value is PlatformSession.SignedIn
            }.getOrDefault(false)
            // Modo plataforma: grava a sessão sintética no holder para o shell/telas locais usarem a identidade da VPS.
            if (restored == null && platformSignedIn) {
                (platformRepository.session.value as? PlatformSession.SignedIn)?.let {
                    sessionHolder.set(PlatformIdentity.session(it.user))
                    _platformMode.value = true
                }
            }
            // Só aplica o bloqueio local quando a abertura será efetivamente no modo local (dashboard).
            if (restored != null && !platformSignedIn) {
                // Abertura a frio com sessão lembrada: exige desbloqueio se houver proteção configurada.
                val s = settingsRepository.settings.first()
                _hasPin.value = runCatching { authRepository.hasPin() }.getOrDefault(false)
                if (s.biometricLock || _hasPin.value) _locked.value = true
            }
            val target = when {
                // Sessão de plataforma OU local → abre o SHELL completo (dashboard/menu/barra).
                platformSignedIn || restored != null -> Routes.DASHBOARD
                // Sem sessão: tela de login unificada (abre com o modo PLATAFORMA já selecionado).
                else -> Routes.LOGIN
            }
            _startRoute.value = target

            // Reflete login/logout de plataforma em tempo real na sessão sintética do holder.
            var wasSignedIn = platformSignedIn
            platformRepository.session.collect { plat ->
                val justSignedIn = plat is PlatformSession.SignedIn && !wasSignedIn
                wasSignedIn = plat is PlatformSession.SignedIn
                when {
                    plat is PlatformSession.SignedIn && _platformMode.value ->
                        sessionHolder.set(PlatformIdentity.session(plat.user)) // mantém sincronizada
                    // Login de PLATAFORMA acabou de acontecer (inclusive o 1º login num app recém-instalado, quando
                    // outra parte já montou uma sessão no holder): entra no modo plataforma. Login local não gera
                    // PlatformSession.SignedIn, então não cai aqui.
                    justSignedIn || (plat is PlatformSession.SignedIn && sessionHolder.current == null) -> {
                        sessionHolder.set(PlatformIdentity.session((plat as PlatformSession.SignedIn).user))
                        _platformMode.value = true
                    }
                    plat !is PlatformSession.SignedIn && _platformMode.value -> {
                        sessionHolder.set(null)
                        _platformMode.value = false
                    }
                }
            }
        }
        // Login / troca de empresa → restaura (ou cria) as sessões de pregão da empresa ativa.
        // No modo plataforma NÃO semeia sessões de pregão locais (a identidade é sintética).
        viewModelScope.launch {
            session.map { it?.activeCompany?.id }.distinctUntilChanged().collect { companyId ->
                if (companyId != null && !_platformMode.value) runCatching { liveSessionManager.restoreOrSeed(companyId) }
                else if (companyId == null) _locked.value = false
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            // Modo plataforma: encerra a sessão da VPS (o collector limpa o holder sintético e sai do modo).
            if (_platformMode.value) {
                runCatching { platformRepository.logout() }
                return@launch
            }
            val provider = session.value?.user?.provider
            authRepository.logout()
            // Conta Google: limpa o estado de credencial para que o próximo login mostre o seletor de contas.
            if (provider != null) runCatching { identitySignOut.signOut(provider) }
        }
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
        // No modo plataforma não aplicamos o bloqueio local (PIN/biometria é fluxo do modo local).
        if (session.value == null || _locked.value || _platformMode.value) return
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
}
