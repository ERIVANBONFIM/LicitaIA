package com.licitaia.feature.bidding.engine

import com.licitaia.connector.api.BidSubmission
import com.licitaia.connector.api.ConnectorRegistry
import com.licitaia.connector.api.HumanConfirmation
import com.licitaia.connector.api.LiveSessionHandle
import com.licitaia.connector.api.PortalLiveEvent
import com.licitaia.connector.mock.MockSessionControl
import com.licitaia.core.ui.nav.Routes
import com.licitaia.domain.bidding.BidContext
import com.licitaia.domain.bidding.BidDecision
import com.licitaia.domain.bidding.BidRuleEngine
import com.licitaia.domain.bidding.DemoSessionSpecs
import com.licitaia.domain.live.LiveSessionManager
import com.licitaia.domain.live.LiveSessionStore
import com.licitaia.domain.model.AuctioneerMessage
import com.licitaia.domain.model.AuditAction
import com.licitaia.domain.model.AuditOrigin
import com.licitaia.domain.model.AuditResult
import com.licitaia.domain.model.BidAuthorization
import com.licitaia.domain.model.BidEvent
import com.licitaia.domain.model.BidEventType
import com.licitaia.domain.model.BidResult
import com.licitaia.domain.model.BidRule
import com.licitaia.domain.model.LiveSession
import com.licitaia.domain.model.LiveSessionSpec
import com.licitaia.domain.model.LiveStatus
import com.licitaia.domain.model.NotificationCategory
import com.licitaia.domain.model.RobotStatus
import com.licitaia.domain.repository.AppNotifier
import com.licitaia.domain.repository.AuditRepository
import com.licitaia.domain.repository.AuthRepository
import com.licitaia.domain.repository.MessageRepository
import com.licitaia.domain.repository.SettingsRepository
import com.licitaia.domain.util.Formatters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * Motor de pregões ao vivo. Cada sessão é um ator independente: estado, handle do portal,
 * fila de comandos, log, CAPTCHA e autorização pendente são exclusivos da sessão. Robôs
 * permanecem inativos em modo pessoal; a operação ocorre no navegador oficial.
 */
@Singleton
class LiveSessionManagerImpl @Inject constructor(
    private val connectors: ConnectorRegistry,
    private val store: LiveSessionStore,
    private val notifier: AppNotifier,
    private val audit: AuditRepository,
    private val messages: MessageRepository,
    private val settings: SettingsRepository,
    private val auth: AuthRepository,
) : LiveSessionManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val runtimes = ConcurrentHashMap<String, SessionRuntime>()
    private val states = MutableStateFlow<Map<String, LiveSession>>(emptyMap())
    private val activeCompany = MutableStateFlow<Long?>(null)
    private val loadedCompanies = ConcurrentHashMap.newKeySet<Long>()
    private val restoreMutex = Mutex()

    override val sessions: StateFlow<List<LiveSession>> =
        combine(states, activeCompany) { map, company ->
            map.values.filter { it.companyId == company }.sortedBy { it.startedAt }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        scope.launch { auth.session.collect { session ->
            val company = session?.activeCompany?.id
            runtimes.values.filter { it.state.companyId != company }.forEach { runtime ->
                runtime.teardown(); runtimes.remove(runtime.id); states.update { it - runtime.id }
            }
            loadedCompanies.removeIf { it != company }
            activeCompany.value = company
        } }
    }

    override fun observeSession(sessionId: String): Flow<LiveSession?> =
        combine(states, auth.session) { map, session -> map[sessionId]?.takeIf { it.companyId == session?.activeCompany?.id && session.user.demo == false && it.companyId in session.user.companyIds } }.distinctUntilChanged()

    override fun observeEvents(sessionId: String): Flow<List<BidEvent>> = store.observeEvents(sessionId)

    private fun requireCompany(companyId: Long) {
        val session = auth.session.value ?: error("Sessão encerrada.")
        check(!session.user.demo && companyId == session.activeCompany.id && companyId in session.user.companyIds) { "Empresa não autorizada." }
        check(com.licitaia.domain.security.Rbac.can(session.user.role, com.licitaia.domain.security.Permission.OPERAR_SESSOES)) { "Perfil sem permissão para operar sessões." }
    }
    private fun authorizedRuntime(id: String): SessionRuntime? = runtimes[id]?.also { requireCompany(it.state.companyId) }

    // ------------------------------------------------------------------ ciclo de vida

    override suspend fun restoreOrSeed(companyId: Long): Unit = restoreMutex.withLock {
        requireCompany(companyId)
        activeCompany.value = companyId
        if (!loadedCompanies.add(companyId)) return@withLock
        val saved = try {
            store.loadSessions(companyId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        val alive = saved.filter { it.status != LiveStatus.ENCERRADA && !runtimes.containsKey(it.id) }
        val hasAny = alive.isNotEmpty() || runtimes.values.any { it.state.companyId == companyId }
        if (hasAny) {
            alive.forEach { restore(it) }
        } else {
            // No fabricated auctions or competitors are seeded in personal mode.
        }
    }

    override suspend fun openSession(spec: LiveSessionSpec): String { requireCompany(spec.companyId); return create(spec).id }

    override suspend fun closeSession(sessionId: String) {
        val runtime = authorizedRuntime(sessionId) ?: return
        runtime.call { closeByUser() }
        runtime.teardown()
        runtimes.remove(sessionId, runtime)
        states.update { it - sessionId }
        runCatching { notifier.cancelSessionAlerts(sessionId) }
    }

    private fun create(spec: LiveSessionSpec, startedAtOffset: Long = 0): SessionRuntime {
        val now = System.currentTimeMillis() + startedAtOffset
        val session = LiveSession(
            id = "ls-" + UUID.randomUUID().toString().replace("-", "").take(12),
            companyId = spec.companyId,
            tenderId = spec.tenderId,
            portal = spec.portal,
            tenderNumber = spec.tenderNumber,
            agency = spec.agency,
            itemLabel = spec.itemLabel,
            objectDescription = spec.objectDescription,
            status = LiveStatus.EM_DISPUTA,
            robotStatus = RobotStatus.INATIVO,
            position = 0,
            competitors = spec.competitors,
            ourLastBid = null,
            bestBid = null,
            rule = spec.rule.copy(simulation = true),
            remainingSeconds = null,
            startedAt = now,
            updatedAt = now,
        )
        return SessionRuntime(session, restored = false).also { runtimes[session.id] = it; it.start() }
    }

    private fun restore(saved: LiveSession) {
        var s = saved.copy(rule = saved.rule.copy(simulation = true), pendingAuthorization = null)
        // Segurança: nenhum robô volta a operar sozinho após o app reabrir.
        if (s.robotStatus == RobotStatus.ATIVO || s.robotStatus == RobotStatus.AGUARDANDO_AUTORIZACAO) {
            s = s.copy(robotStatus = RobotStatus.PAUSADO)
        }
        if (s.status == LiveStatus.AGUARDANDO || s.status == LiveStatus.PAUSADA) s = s.copy(status = LiveStatus.EM_DISPUTA)
        SessionRuntime(s, restored = true, robotWasRunning = saved.robotRunning).also { runtimes[s.id] = it; it.start() }
    }

    // ------------------------------------------------------------------ API pública (delegada ao ator da sessão)

    override suspend fun startRobot(sessionId: String) { authorizedRuntime(sessionId)?.call { startRobot() } }
    override suspend fun pauseRobot(sessionId: String, reason: String) { authorizedRuntime(sessionId)?.call { pauseRobot(reason, emergency = false) } }
    override suspend fun resumeRobot(sessionId: String) { startRobot(sessionId) }
    override suspend fun stopRobot(sessionId: String) { authorizedRuntime(sessionId)?.call { stopRobot() } }
    override suspend fun takeOverManually(sessionId: String) { authorizedRuntime(sessionId)?.call { takeOver() } }

    override suspend fun submitManualBid(sessionId: String, value: Double): BidResult =
        authorizedRuntime(sessionId)?.call { manualBid(value) } ?: BidResult.Rejected("Sessão não encontrada.")

    override suspend fun updateRule(sessionId: String, rule: BidRule) {
        val runtime = authorizedRuntime(sessionId) ?: return
        val role = auth.session.value?.user?.role ?: return
        check(com.licitaia.domain.security.Rbac.can(role, com.licitaia.domain.security.Permission.ALTERAR_REGRAS)) { "Perfil sem permissão para alterar regras." }
        if (runtime.state.rule.floorPrice != rule.floorPrice) check(com.licitaia.domain.security.Rbac.can(role, com.licitaia.domain.security.Permission.APROVAR_PISO)) { "Perfil sem permissão para alterar piso." }
        runtime.call { updateRule(rule) }
    }
    override suspend fun confirmCaptchaResolved(sessionId: String) { authorizedRuntime(sessionId)?.call { captchaResolved() } }

    override suspend fun triggerDemoCaptcha(sessionId: String) { /* No simulated challenges in personal mode. */ }

    override suspend fun respondAuthorization(sessionId: String, authorizationId: String, approved: Boolean) {
        authorizedRuntime(sessionId) // Ownership check; binding actions require the official portal.
    }

    override suspend fun pauseAllRobots(reason: String): Int {
        var paused = 0
        for (runtime in runtimes.values.filter { it.state.companyId == auth.session.value?.activeCompany?.id }) {
            if (runtime.call { pauseRobot(reason, emergency = true) } == true) paused++
        }
        safely {
            audit.record(
                AuditAction.EMERGENCIA, AuditResult.SUCESSO, AuditOrigin.USUARIO,
                reason = reason, newValue = "$paused robô(s) pausado(s)", details = "Sessões mantidas abertas.",
            )
        }
        if (paused > 0) {
            safely {
                notifier.notify(
                    NotificationCategory.CRITICA, "Parada de emergência",
                    "$paused robô(s) pausado(s). As sessões continuam abertas para controle manual.",
                    critical = false, route = Routes.WARROOM, companyId = activeCompany.value,
                )
            }
        }
        return paused
    }

    private suspend inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private fun currentUserName(): String = auth.session.value?.user?.name ?: "Operador"

    // ================================================================== ator de UMA sessão

    private inner class SessionRuntime(
        initial: LiveSession,
        private val restored: Boolean,
        private val robotWasRunning: Boolean = false,
    ) {
        val id: String = initial.id
        @Volatile var state: LiveSession = initial
            private set

        private val mailbox = Channel<suspend () -> Unit>(Channel.UNLIMITED)
        private var actor: Job? = null
        private var collector: Job? = null
        private var captchaAlerts: Job? = null
        private var handle: LiveSessionHandle? = null
        private var lastBidAt: Long? = null
        private var lastAuthNotifyAt = 0L
        private var ticksSincePersist = 0
        private var robotBeforeCaptcha: RobotStatus? = null

        private val connector get() = connectors.get(state.portal)
        private val now get() = System.currentTimeMillis()

        fun start() {
            states.update { it + (id to state) }
            actor = scope.launch {
                try {
                    open()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    critical("Falha ao abrir a sessão no portal: ${e.message ?: e.javaClass.simpleName}")
                }
                for (task in mailbox) {
                    try {
                        task()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        runCatching { critical("Erro interno na sessão: ${e.message ?: e.javaClass.simpleName}") }
                    }
                }
            }
        }

        /** Executa [block] dentro do ator da sessão e devolve o resultado (null se a sessão já foi removida). */
        suspend fun <T> call(block: suspend SessionRuntime.() -> T): T? {
            val reply = CompletableDeferred<T>()
            val sent = mailbox.trySend {
                try {
                    reply.complete(block())
                } catch (e: Throwable) {
                    reply.completeExceptionally(e)
                    if (e !is CancellationException) throw e
                }
            }
            if (sent.isFailure) return null
            return try { reply.await() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        }

        fun teardown() {
            captchaAlerts?.cancel()
            collector?.cancel()
            mailbox.close()
            val h = handle
            handle = null
            scope.launch { runCatching { h?.close() } }
        }

        private suspend fun open() {
            if (state.status == LiveStatus.ERRO || state.status == LiveStatus.ENCERRADA) return
            set { copy(robotStatus = RobotStatus.CONTROLE_MANUAL, remainingSeconds = null, pendingAuthorization = null) }
            persist()
            log(BidEventType.SESSION_OPENED, "Registro local assistido. Nenhuma conexão autenticada com o portal; acompanhe e opere no site oficial.")
            persist()
        }

        private fun spec() = LiveSessionSpec(
            companyId = state.companyId, tenderId = state.tenderId, portal = state.portal,
            tenderNumber = state.tenderNumber, agency = state.agency, itemLabel = state.itemLabel,
            objectDescription = state.objectDescription, rule = state.rule, competitors = state.competitors,
        )

        // ------------------------------------------------------------ utilitários de estado

        private fun set(transform: LiveSession.() -> LiveSession) {
            state = state.transform().copy(updatedAt = now)
            states.update { it + (id to state) }
        }

        private suspend fun persist() {
            ticksSincePersist = 0
            safely { store.saveSession(state) }
        }

        suspend fun log(type: BidEventType, description: String, value: Double? = null, actor: String = "Sistema") {
            safely { store.appendEvent(BidEvent(sessionId = id, timestamp = now, type = type, value = value, actor = actor, description = description)) }
        }

        private suspend fun auditRecord(
            action: AuditAction,
            result: AuditResult = AuditResult.SUCESSO,
            origin: AuditOrigin = AuditOrigin.USUARIO,
            previousValue: String? = null,
            newValue: String? = null,
            reason: String? = null,
            details: String = "",
        ) {
            safely {
                audit.record(
                    action, result, origin, portal = state.portal, tenderNumber = state.tenderNumber, item = state.itemLabel,
                    previousValue = previousValue, newValue = newValue, reason = reason, details = details,
                )
            }
        }

        private suspend fun notify(category: NotificationCategory, title: String, body: String, critical: Boolean = false, route: String? = Routes.liveSession(id)) {
            safely { notifier.notify(category, title, body, critical, route, sessionId = id, companyId = state.companyId) }
        }

        private val sessionLabel get() = "${state.portal.shortName} · ${state.tenderNumber} · ${state.itemLabel}"

        private suspend fun critical(message: String) {
            captchaAlerts?.cancel()
            set { copy(status = LiveStatus.ERRO, robotStatus = RobotStatus.ERRO, lastError = message, pendingAuthorization = null) }
            log(BidEventType.ERROR, message)
            auditRecord(AuditAction.ERRO, AuditResult.FALHA, AuditOrigin.SISTEMA, details = message)
            notify(NotificationCategory.CRITICA, "Erro crítico na sessão", "$sessionLabel: $message. O robô foi interrompido.", critical = true)
            persist()
        }

        private val sessionOpen get() = state.status != LiveStatus.ERRO && state.status != LiveStatus.ENCERRADA

        // ------------------------------------------------------------ eventos do portal

        private suspend fun onPortalEvent(event: PortalLiveEvent) {
            if (!sessionOpen) return
            when (event) {
                is PortalLiveEvent.CompetitorBid -> onCompetitorBid(event)
                is PortalLiveEvent.TimerTick -> {
                    set { copy(remainingSeconds = event.remainingSeconds) }
                    if (++ticksSincePersist >= 20) persist()
                    evaluateRobot()
                }
                is PortalLiveEvent.Message -> onMessage(event)
                PortalLiveEvent.CaptchaRequired -> onCaptcha("CAPTCHA")
                PortalLiveEvent.MfaRequired -> onCaptcha("MFA")
                is PortalLiveEvent.Error -> {
                    if (event.critical) {
                        critical(event.message)
                    } else {
                        set { copy(lastError = event.message) }
                        log(BidEventType.ERROR, event.message, actor = "Portal")
                    }
                }
                is PortalLiveEvent.Closed -> onClosed(event)
            }
        }

        private suspend fun onCompetitorBid(event: PortalLiveEvent.CompetitorBid) {
            val wasWinning = state.isWinning
            val snapshot = runCatching { connector.readCurrentBidState(id) }.getOrNull()
            val best = min(state.bestBid ?: event.value, event.value)
            val position = snapshot?.ourPosition ?: when {
                state.ourLastBid == null -> 0
                else -> 2
            }
            set { copy(bestBid = snapshot?.bestBid ?: best, position = position, competitors = snapshot?.competitors ?: competitors) }
            log(BidEventType.COMPETITOR_BID, "${event.alias} ofertou ${Formatters.brl(event.value)}.", event.value, actor = event.alias)
            if (wasWinning && !state.isWinning) {
                log(BidEventType.POSITION_CHANGED, "Perdemos a 1ª posição para ${event.alias}.", actor = "Portal")
            }
            val pending = state.pendingAuthorization
            if (pending != null && event.value <= pending.proposedValue + 0.001) {
                set { copy(pendingAuthorization = null, robotStatus = if (robotStatus == RobotStatus.AGUARDANDO_AUTORIZACAO) RobotStatus.ATIVO else robotStatus) }
                log(BidEventType.AUTH_DENIED, "Autorização expirada: o melhor lance mudou antes da decisão.", pending.proposedValue, actor = "Robô")
            }
            persist()
            evaluateRobot()
        }

        private suspend fun onMessage(event: PortalLiveEvent.Message) {
            val m = event.message
            var messageId: Long? = null
            safely {
                messageId = messages.insert(
                    AuctioneerMessage(
                        companyId = state.companyId, sessionId = id, portal = state.portal, tenderNumber = state.tenderNumber,
                        sender = m.sender, body = m.body, receivedAt = m.timestamp, urgent = m.urgent, responseDeadline = m.responseDeadline,
                    ),
                )
            }
            set { copy(unreadMessages = unreadMessages + 1) }
            log(BidEventType.MESSAGE, m.body, actor = m.sender)
            notify(
                NotificationCategory.MENSAGENS,
                if (m.urgent) "Mensagem urgente do pregoeiro" else "Mensagem do pregoeiro",
                "${state.portal.shortName} · ${state.tenderNumber}: ${m.body}",
                critical = m.urgent,
                route = messageId?.let { Routes.message(it) } ?: Routes.MESSAGES,
            )
            persist()
        }

        private suspend fun onClosed(event: PortalLiveEvent.Closed) {
            captchaAlerts?.cancel()
            val ours = state.ourLastBid
            val finalValue = event.finalValue
            val won = ours != null && finalValue != null && ours <= finalValue + 0.001
            set {
                copy(
                    status = LiveStatus.ENCERRADA, robotStatus = RobotStatus.ENCERRADO, remainingSeconds = 0,
                    pendingAuthorization = null, captchaPending = false, captchaSince = null,
                    bestBid = event.finalValue ?: bestBid, position = if (won) 1 else position,
                )
            }
            val result = if (won) "VENCEMOS com ${Formatters.brl(event.finalValue)}" else "melhor lance final ${Formatters.brl(event.finalValue)} (${event.winnerAlias ?: "sem vencedor"})"
            log(BidEventType.SESSION_CLOSED, "Disputa encerrada pelo portal — $result.", event.finalValue, actor = "Portal")
            auditRecord(AuditAction.ROBO_ENCERRADO, origin = AuditOrigin.SISTEMA, details = "Disputa encerrada pelo portal: $result")
            notify(NotificationCategory.SESSOES, if (won) "Disputa encerrada — vencemos!" else "Disputa encerrada", "$sessionLabel: $result.")
            runCatching { notifier.cancelSessionAlerts(id) }
            persist()
        }

        // ------------------------------------------------------------ CAPTCHA / MFA

        private suspend fun onCaptcha(kind: String) {
            if (state.captchaPending) return
            robotBeforeCaptcha = state.robotStatus
            set {
                copy(
                    captchaPending = true, captchaSince = now, status = LiveStatus.CAPTCHA_PENDENTE, pendingAuthorization = null,
                    robotStatus = if (robotRunning) RobotStatus.BLOQUEADO_CAPTCHA else robotStatus,
                )
            }
            runCatching { connector.pauseAutomation(id) }
            log(BidEventType.CAPTCHA_DETECTED, "$kind detectado pelo portal. Automação desta sessão pausada até resolução manual.", actor = "Portal")
            auditRecord(AuditAction.CAPTCHA, AuditResult.PENDENTE, AuditOrigin.SISTEMA, details = "$kind pendente; sessão pausada")
            startCaptchaAlerts(notifyNow = true)
            persist()
        }

        private fun startCaptchaAlerts(notifyNow: Boolean) {
            captchaAlerts?.cancel()
            captchaAlerts = scope.launch {
                if (notifyNow) notifyCaptcha()
                settings.settings.map { it.captchaRepeatMinutes }.distinctUntilChanged().collectLatest { minutes ->
                    if (minutes <= 0) return@collectLatest
                    while (true) {
                        delay(minutes * 60_000L)
                        if (!state.captchaPending) return@collectLatest
                        notifyCaptcha()
                    }
                }
            }
        }

        private suspend fun notifyCaptcha() {
            notify(
                NotificationCategory.CAPTCHA, "CAPTCHA aguardando — sessão pausada.",
                "$sessionLabel. Resolva manualmente no portal e confirme no app para retomar.", critical = true,
            )
        }

        suspend fun captchaResolved() {
            if (!state.captchaPending) return
            captchaAlerts?.cancel()
            captchaAlerts = null
            (connector as? MockSessionControl)?.confirmCaptchaResolved(id)
            val resume = state.robotStatus == RobotStatus.BLOQUEADO_CAPTCHA
            set {
                copy(
                    captchaPending = false, captchaSince = null,
                    status = if (status == LiveStatus.CAPTCHA_PENDENTE) LiveStatus.EM_DISPUTA else status,
                    robotStatus = if (resume) RobotStatus.ATIVO else robotStatus,
                )
            }
            robotBeforeCaptcha = null
            runCatching { notifier.cancelSessionAlerts(id) }
            val user = currentUserName()
            log(BidEventType.CAPTCHA_RESOLVED, "CAPTCHA resolvido manualmente por $user." + if (resume) " Automação retomada." else "", actor = user)
            auditRecord(AuditAction.CAPTCHA, AuditResult.SUCESSO, details = "Resolvido manualmente pelo usuário" + if (resume) "; robô retomado" else "")
            persist()
            if (resume) evaluateRobot()
        }

        // ------------------------------------------------------------ controle do robô

        suspend fun startRobot() {
            log(BidEventType.ERROR, "Automação indisponível: opere manualmente no site oficial.")
        }

        /** @return true se havia automação a pausar. */
        suspend fun pauseRobot(reason: String, emergency: Boolean): Boolean {
            val pausable = state.robotRunning || state.robotStatus == RobotStatus.BLOQUEADO_CAPTCHA
            if (!pausable) return false
            // Pausa manual encerra a repetição do alerta de CAPTCHA (o CAPTCHA continua pendente).
            captchaAlerts?.cancel()
            captchaAlerts = null
            set { copy(robotStatus = RobotStatus.PAUSADO, pendingAuthorization = null) }
            val user = currentUserName()
            log(BidEventType.ROBOT_PAUSED, (if (emergency) "PARADA DE EMERGÊNCIA — " else "") + "Robô pausado: $reason.", actor = user)
            auditRecord(AuditAction.ROBO_PAUSADO, reason = reason, details = if (emergency) "Parada de emergência" else "")
            persist()
            return true
        }

        suspend fun resumeRobot() {
            if (state.robotStatus != RobotStatus.PAUSADO || !sessionOpen) return
            val user = currentUserName()
            val blocked = state.captchaPending
            set { copy(robotStatus = if (blocked) RobotStatus.BLOQUEADO_CAPTCHA else RobotStatus.ATIVO) }
            log(BidEventType.ROBOT_RESUMED, "Robô retomado por $user." + if (blocked) " Aguardando resolução do CAPTCHA." else "", actor = user)
            auditRecord(AuditAction.ROBO_ATIVADO, details = "Retomada")
            if (blocked) startCaptchaAlerts(notifyNow = true)
            persist()
            evaluateRobot()
        }

        suspend fun stopRobot() {
            if (state.robotStatus == RobotStatus.ENCERRADO || state.robotStatus == RobotStatus.INATIVO) return
            captchaAlerts?.cancel()
            captchaAlerts = null
            val user = currentUserName()
            set { copy(robotStatus = RobotStatus.ENCERRADO, pendingAuthorization = null) }
            log(BidEventType.ROBOT_STOPPED, "Robô encerrado por $user. A sessão continua aberta.", actor = user)
            auditRecord(AuditAction.ROBO_ENCERRADO)
            persist()
        }

        suspend fun takeOver() {
            if (!sessionOpen) return
            captchaAlerts?.cancel()
            captchaAlerts = null
            runCatching { connector.pauseAutomation(id) }
            val user = currentUserName()
            set { copy(robotStatus = RobotStatus.CONTROLE_MANUAL, pendingAuthorization = null) }
            log(BidEventType.MANUAL_TAKEOVER, "PARAR E ASSUMIR: $user assumiu o controle manual. Nenhum lance automático será enviado.", actor = user)
            auditRecord(AuditAction.CONTROLE_MANUAL, details = "Automação interrompida pelo operador")
            persist()
        }

        suspend fun closeByUser() {
            captchaAlerts?.cancel()
            captchaAlerts = null
            val user = currentUserName()
            if (state.robotRunning || state.robotStatus == RobotStatus.BLOQUEADO_CAPTCHA) {
                log(BidEventType.ROBOT_STOPPED, "Robô encerrado junto com a sessão.", actor = user)
                auditRecord(AuditAction.ROBO_ENCERRADO, details = "Sessão encerrada pelo usuário")
            }
            set { copy(status = LiveStatus.ENCERRADA, robotStatus = RobotStatus.ENCERRADO, pendingAuthorization = null, captchaPending = false, captchaSince = null) }
            log(BidEventType.SESSION_CLOSED, "Sessão encerrada por $user.", actor = user)
            persist()
        }

        // ------------------------------------------------------------ regra / autorização

        suspend fun updateRule(newRule: BidRule) {
            if (!sessionOpen) return
            val rule = newRule.copy(simulation = true)
            if (!BidRuleEngine.isOperable(rule)) {
                log(BidEventType.ERROR, "Regra rejeitada: " + BidRuleEngine.validateRule(rule).joinToString(" "), actor = currentUserName())
                return
            }
            val old = state.rule
            if (old == rule) return
            val user = currentUserName()
            set { copy(rule = rule, pendingAuthorization = null, robotStatus = if (robotStatus == RobotStatus.AGUARDANDO_AUTORIZACAO) RobotStatus.ATIVO else robotStatus) }
            if (old.floorPrice != rule.floorPrice) {
                log(BidEventType.RULE_CHANGED, "Piso alterado por $user: ${Formatters.brl(old.floorPrice)} → ${Formatters.brl(rule.floorPrice)}.", rule.floorPrice, actor = user)
                auditRecord(AuditAction.MUDANCA_PISO, previousValue = Formatters.brl(old.floorPrice), newValue = Formatters.brl(rule.floorPrice))
            }
            val changes = buildList {
                if (old.mode != rule.mode) add("modo ${old.mode.label} → ${rule.mode.label}")
                if (old.strategy != rule.strategy) add("estratégia ${old.strategy.label} → ${rule.strategy.label}")
                if (old.initialPrice != rule.initialPrice) add("preço inicial ${Formatters.brl(old.initialPrice)} → ${Formatters.brl(rule.initialPrice)}")
                if (old.costPrice != rule.costPrice) add("custo ${Formatters.brl(old.costPrice)} → ${Formatters.brl(rule.costPrice)}")
                if (old.reductionValue != rule.reductionValue) add("redução ${Formatters.brl(old.reductionValue)} → ${Formatters.brl(rule.reductionValue)}")
                if (old.minMarginPct != rule.minMarginPct) add("margem mínima ${Formatters.percent(old.minMarginPct)} → ${Formatters.percent(rule.minMarginPct)}")
                if (old.lossLimit != rule.lossLimit) add("limite de perda ${Formatters.brl(old.lossLimit)} → ${Formatters.brl(rule.lossLimit)}")
                if (old.minIntervalSeconds != rule.minIntervalSeconds) add("intervalo ${old.minIntervalSeconds}s → ${rule.minIntervalSeconds}s")
                if (old.authorizationThresholdPct != rule.authorizationThresholdPct) add("limiar de autorização ${Formatters.percent(old.authorizationThresholdPct)} → ${Formatters.percent(rule.authorizationThresholdPct)}")
            }
            if (changes.isNotEmpty()) {
                log(BidEventType.RULE_CHANGED, "Regra alterada por $user: ${changes.joinToString("; ")}.", actor = user)
                auditRecord(AuditAction.MUDANCA_REGRA, newValue = changes.joinToString("; "))
            }
            persist()
            evaluateRobot()
        }

        suspend fun respondAuthorization(authorizationId: String, approved: Boolean) {
            val pending = state.pendingAuthorization ?: return
            if (pending.id != authorizationId) return
            val user = currentUserName()
            if (!approved) {
                set { copy(pendingAuthorization = null, robotStatus = RobotStatus.PAUSADO) }
                log(BidEventType.AUTH_DENIED, "Autorização negada por $user para ${Formatters.brl(pending.proposedValue)}. Robô pausado.", pending.proposedValue, actor = user)
                auditRecord(AuditAction.REJEICAO, newValue = Formatters.brl(pending.proposedValue), reason = pending.reason)
                runCatching { notifier.cancelSessionAlerts(id) }
                persist()
                return
            }
            set { copy(pendingAuthorization = null, robotStatus = if (robotStatus == RobotStatus.AGUARDANDO_AUTORIZACAO) RobotStatus.ATIVO else robotStatus) }
            log(BidEventType.AUTH_GRANTED, "Autorização concedida por $user para ${Formatters.brl(pending.proposedValue)}.", pending.proposedValue, actor = user)
            auditRecord(AuditAction.APROVACAO, newValue = Formatters.brl(pending.proposedValue), reason = pending.reason)
            runCatching { notifier.cancelSessionAlerts(id) }
            submit(pending.proposedValue, actor = "Robô", origin = AuditOrigin.ROBO, confirmation = HumanConfirmation(user, now), note = "Lance autorizado por $user")
            persist()
            evaluateRobot()
        }

        // ------------------------------------------------------------ lances

        suspend fun manualBid(value: Double): BidResult {
            return BidResult.Rejected("Envio indisponível. Confira o valor e confirme o lance no site oficial.")
        }

        /** Caminho único de envio (robô, autorizado ou manual): valida piso/CAPTCHA/melhor lance e registra tudo. */
        private suspend fun submit(value: Double, actor: String, origin: AuditOrigin, confirmation: HumanConfirmation?, note: String): BidResult {
            val blocked = BidRuleEngine.validateBid(state.rule, value, state.bestBid, state.captchaPending)
            if (blocked != null) {
                lastBidAt = now
                log(BidEventType.BID_BLOCKED, "$note bloqueado (${Formatters.brl(value)}): $blocked", value, actor = actor)
                auditRecord(AuditAction.LANCE, AuditResult.BLOQUEADO, origin, newValue = Formatters.brl(value), reason = blocked)
                return BidResult.Rejected(blocked)
            }
            val result = try {
                connector.submitBid(id, state.itemLabel, value, confirmation)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BidSubmission.Rejected("Falha de comunicação com o portal: ${e.message ?: "erro desconhecido"}")
            }
            return when (result) {
                is BidSubmission.Accepted -> {
                    lastBidAt = now
                    val wasWinning = state.isWinning
                    set { copy(ourLastBid = result.value, bestBid = min(bestBid ?: result.value, result.value), position = result.position) }
                    val margin = Formatters.percent(state.rule.marginPct(result.value))
                    log(BidEventType.OUR_BID, "$note (SIMULAÇÃO): ${Formatters.brl(result.value)} — ${result.position}º lugar, margem $margin.", result.value, actor = actor)
                    if (!wasWinning && state.isWinning) log(BidEventType.POSITION_CHANGED, "Assumimos a 1ª posição.", actor = actor)
                    auditRecord(AuditAction.LANCE, AuditResult.SUCESSO, origin, newValue = Formatters.brl(result.value), details = "$note; simulação; posição ${result.position}")
                    persist()
                    BidResult.Accepted(result.value, result.position)
                }
                is BidSubmission.Rejected -> {
                    lastBidAt = now
                    log(BidEventType.BID_BLOCKED, "$note recusado pelo portal (${Formatters.brl(value)}): ${result.reason}", value, actor = "Portal")
                    auditRecord(AuditAction.LANCE, AuditResult.FALHA, origin, newValue = Formatters.brl(value), reason = result.reason)
                    BidResult.Rejected(result.reason)
                }
                BidSubmission.CaptchaRequired -> {
                    onCaptcha("CAPTCHA")
                    BidResult.Rejected("O portal exige CAPTCHA antes de aceitar lances. Sessão pausada.")
                }
            }
        }

        private suspend fun evaluateRobot() {
            if (state.robotStatus != RobotStatus.ATIVO || state.status != LiveStatus.EM_DISPUTA) return
            val decision = BidRuleEngine.decide(
                BidContext(
                    rule = state.rule, ourLastBid = state.ourLastBid, bestBid = state.bestBid, position = state.position,
                    captchaPending = state.captchaPending, nowMillis = now, lastBidAtMillis = lastBidAt,
                    minDecrement = 0.01,
                ),
            )
            when (decision) {
                is BidDecision.Place -> submit(decision.value, actor = "Robô", origin = AuditOrigin.ROBO, confirmation = null, note = "Lance automático — ${decision.reason}")
                is BidDecision.RequestAuthorization -> requestAuthorization(decision.value, decision.reason)
                is BidDecision.StopAtFloor -> {
                    set { copy(robotStatus = RobotStatus.PARADO_NO_PISO) }
                    log(BidEventType.FLOOR_REACHED, "${decision.reason} Piso: ${Formatters.brl(BidRuleEngine.effectiveFloor(state.rule))}.", BidRuleEngine.effectiveFloor(state.rule), actor = "Robô")
                    auditRecord(AuditAction.ROBO_PAUSADO, AuditResult.BLOQUEADO, AuditOrigin.ROBO, reason = "Piso atingido")
                    notify(NotificationCategory.LANCES, "Robô parado no piso", "$sessionLabel: cobrir o concorrente exigiria vender abaixo do piso.", critical = false)
                    persist()
                }
                is BidDecision.Blocked -> if (state.captchaPending) onCaptcha("CAPTCHA")
                is BidDecision.Suggest, is BidDecision.Wait -> Unit
            }
        }

        private suspend fun requestAuthorization(value: Double, reason: String) {
            val authorization = BidAuthorization(
                id = UUID.randomUUID().toString(), sessionId = id, proposedValue = value, reason = reason, requestedAt = now,
            )
            set { copy(pendingAuthorization = authorization, robotStatus = RobotStatus.AGUARDANDO_AUTORIZACAO) }
            log(BidEventType.AUTH_REQUESTED, "Robô solicita autorização para ${Formatters.brl(value)}: $reason", value, actor = "Robô")
            if (now - lastAuthNotifyAt > AUTH_NOTIFY_THROTTLE_MS) {
                lastAuthNotifyAt = now
                notify(
                    NotificationCategory.LANCES, "Autorização de lance pendente",
                    "$sessionLabel: ${Formatters.brl(value)} aguardando sua decisão. $reason",
                )
            }
            persist()
        }
    }

    private companion object {
        const val AUTH_NOTIFY_THROTTLE_MS = 45_000L
    }
}
