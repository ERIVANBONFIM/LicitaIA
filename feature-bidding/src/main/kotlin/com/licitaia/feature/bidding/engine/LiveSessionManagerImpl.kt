package com.licitaia.feature.bidding.engine

import com.licitaia.core.ui.nav.Routes
import com.licitaia.domain.bidding.AssistedBidding
import com.licitaia.domain.bidding.AssistedBidding.AlertKind
import com.licitaia.domain.bidding.BidRuleEngine
import com.licitaia.domain.live.LiveSessionManager
import com.licitaia.domain.live.LiveSessionStore
import com.licitaia.domain.model.AuditAction
import com.licitaia.domain.model.AuditOrigin
import com.licitaia.domain.model.AuditResult
import com.licitaia.domain.model.BidEvent
import com.licitaia.domain.model.BidEventType
import com.licitaia.domain.model.BidResult
import com.licitaia.domain.model.BidRule
import com.licitaia.domain.model.LiveSession
import com.licitaia.domain.model.LiveSessionSpec
import com.licitaia.domain.model.LiveStatus
import com.licitaia.domain.model.NotificationCategory
import com.licitaia.domain.model.RobotMode
import com.licitaia.domain.model.RobotStatus
import com.licitaia.domain.model.Segment
import com.licitaia.domain.model.TenderStatus
import com.licitaia.domain.model.UserRole
import com.licitaia.domain.repository.AppNotifier
import com.licitaia.domain.repository.AuditRepository
import com.licitaia.domain.repository.AuthRepository
import com.licitaia.domain.repository.CompetitionRepository
import com.licitaia.domain.repository.TenderRepository
import com.licitaia.domain.security.Permission
import com.licitaia.domain.security.Rbac
import com.licitaia.domain.util.Formatters
import com.licitaia.feature.bidding.service.AssistedSessionKeepAlive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Motor de pregões em MODO ASSISTIDO. Cada sessão é um ator independente (estado, fila de comandos,
 * log e cronômetro exclusivos). Nada aqui fala com portal: os lances são registrados pelo usuário
 * depois de dados no site oficial; o motor valida piso, calcula margem, alerta e audita.
 */
@Singleton
class LiveSessionManagerImpl @Inject constructor(
    private val store: LiveSessionStore,
    private val notifier: AppNotifier,
    private val audit: AuditRepository,
    private val auth: AuthRepository,
    private val competition: CompetitionRepository,
    private val tenders: TenderRepository,
    private val keepAlive: AssistedSessionKeepAlive,
) : LiveSessionManager {

    /** Construtor para testes JVM: permite trocar o dispatcher e o relógio (sem Foreground Service). */
    internal constructor(
        store: LiveSessionStore,
        notifier: AppNotifier,
        audit: AuditRepository,
        auth: AuthRepository,
        competition: CompetitionRepository,
        tenders: TenderRepository,
        dispatcher: CoroutineDispatcher,
        clock: () -> Long,
        keepAlive: AssistedSessionKeepAlive = AssistedSessionKeepAlive.None,
    ) : this(store, notifier, audit, auth, competition, tenders, keepAlive) {
        this.clock = clock
        this.scope = CoroutineScope(SupervisorJob() + dispatcher)
        bindAuth()
        bindKeepAlive()
    }

    private var clock: () -> Long = { System.currentTimeMillis() }
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val runtimes = ConcurrentHashMap<String, SessionRuntime>()
    private val states = MutableStateFlow<Map<String, LiveSession>>(emptyMap())
    private val activeCompany = MutableStateFlow<Long?>(null)
    private val loadedCompanies = ConcurrentHashMap.newKeySet<Long>()
    private val restoreMutex = Mutex()
    private val muted = MutableStateFlow(false)
    private var authJob: Job? = null
    private var keepAliveJob: Job? = null

    override val sessions: StateFlow<List<LiveSession>> by lazy {
        combine(states, activeCompany) { map, company ->
            map.values.filter { it.companyId == company }.sortedBy { it.startedAt }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    }

    override val alertsMuted: StateFlow<Boolean> = muted.asStateFlow()

    init {
        bindAuth()
        bindKeepAlive()
    }

    private fun bindAuth() {
        authJob?.cancel()
        authJob = scope.launch {
            auth.session.collect { session ->
                val company = session?.activeCompany?.id
                runtimes.values.filter { it.state.companyId != company }.forEach { runtime ->
                    runtime.teardown(); runtimes.remove(runtime.id); states.update { it - runtime.id }
                }
                loadedCompanies.removeIf { it != company }
                if (activeCompany.value != company) muted.value = false
                activeCompany.value = company
            }
        }
    }

    /**
     * Foreground Service: ligado enquanto houver, na empresa ativa, sessão aberta com cronômetro em
     * andamento ou alerta ativo; desligado ao zerar (encerramento, logout/troca de empresa — que
     * desmontam as sessões — ou "pausar alertas de todas"). Sessões restauradas voltam com o
     * cronômetro parado, portanto não religam o serviço sozinhas.
     */
    private fun bindKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = scope.launch {
            combine(states, activeCompany, muted) { map, company, muted ->
                if (muted || company == null) 0 else map.values.count { it.companyId == company && isActivelyTracked(it) }
            }.distinctUntilChanged().collect { count ->
                runCatching { keepAlive.update(count) }
            }
        }
    }

    private fun isActivelyTracked(s: LiveSession): Boolean =
        s.isOpen && (s.timerRunning || AssistedBidding.activeAlerts(s).isNotEmpty())

    override fun observeSession(sessionId: String): Flow<LiveSession?> =
        combine(states, auth.session) { map, session ->
            map[sessionId]?.takeIf { it.companyId == session?.activeCompany?.id && session.user.demo == false && it.companyId in session.user.companyIds }
        }.distinctUntilChanged()

    override fun observeEvents(sessionId: String): Flow<List<BidEvent>> = store.observeEvents(sessionId)

    /** Usuário logado, fora do modo demo, com a empresa ativa = [companyId]. Basta para VISUALIZAR/restaurar. */
    private fun requireMember(companyId: Long): UserRole {
        val session = auth.session.value ?: error("Sessão encerrada.")
        check(!session.user.demo && companyId == session.activeCompany.id && companyId in session.user.companyIds) { "Empresa não autorizada." }
        return session.user.role
    }

    /** Membro da empresa com permissão de OPERAR sessões (registrar lances, cronômetro, encerrar). */
    private fun requireCompany(companyId: Long) {
        val role = requireMember(companyId)
        check(Rbac.can(role, Permission.OPERAR_SESSOES)) { "Perfil sem permissão para operar sessões." }
    }

    private fun authorizedRuntime(id: String): SessionRuntime? = runtimes[id]?.also { requireCompany(it.state.companyId) }

    // ------------------------------------------------------------------ ciclo de vida

    override suspend fun restoreOrSeed(companyId: Long): Unit = restoreMutex.withLock {
        // Visualizar/restaurar exige apenas pertencer à empresa (Diretoria/Financeiro acompanham sem operar).
        requireMember(companyId)
        activeCompany.value = companyId
        if (!loadedCompanies.add(companyId)) return@withLock
        val saved = try {
            store.loadSessions(companyId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        saved.filter { it.status != LiveStatus.ENCERRADA && !runtimes.containsKey(it.id) }.forEach { restore(it) }
        // Nenhuma sessão fictícia é criada: só o que o usuário abriu.
    }

    override suspend fun openSession(spec: LiveSessionSpec): String {
        requireCompany(spec.companyId)
        val rule = spec.rule.copy(mode = RobotMode.MANUAL, simulation = true)
        check(BidRuleEngine.isOperable(rule)) { BidRuleEngine.validateRule(rule).firstOrNull() ?: "Parâmetros inválidos." }
        val now = clock()
        val session = LiveSession(
            id = "ls-" + UUID.randomUUID().toString().replace("-", "").take(12),
            companyId = spec.companyId,
            tenderId = spec.tenderId,
            portal = spec.portal,
            tenderNumber = spec.tenderNumber.trim(),
            agency = spec.agency.trim(),
            itemLabel = spec.itemLabel.trim(),
            objectDescription = spec.objectDescription.trim(),
            status = LiveStatus.AGUARDANDO,
            robotStatus = RobotStatus.INATIVO,
            position = 0,
            competitors = spec.competitors.coerceAtLeast(0),
            ourLastBid = null,
            bestBid = null,
            rule = rule,
            remainingSeconds = null,
            startedAt = now,
            updatedAt = now,
        )
        val runtime = SessionRuntime(session, restored = false)
        runtimes[session.id] = runtime
        runtime.start()
        return session.id
    }

    override suspend fun closeSession(sessionId: String) {
        val runtime = runtimes[sessionId]
        if (runtime == null) {
            // Sessão já encerrada com resultado (runtime desmontado): apenas sai da lista.
            val finished = states.value[sessionId] ?: return
            requireCompany(finished.companyId)
            states.update { it - sessionId }
            return
        }
        requireCompany(runtime.state.companyId)
        runtime.call { closeByUser() }
        runtime.teardown()
        runtimes.remove(sessionId, runtime)
        states.update { it - sessionId }
        runCatching { notifier.cancelSessionAlerts(sessionId) }
    }

    private fun restore(saved: LiveSession) {
        // Segurança: nada volta a "rodar" sozinho; cronômetro parado até o usuário reiniciar.
        val s = saved.copy(
            rule = saved.rule.copy(mode = RobotMode.MANUAL, simulation = true),
            robotStatus = RobotStatus.INATIVO,
            pendingAuthorization = null,
            timerRunning = false,
            status = if (saved.status == LiveStatus.PAUSADA || saved.status == LiveStatus.CAPTCHA_PENDENTE) LiveStatus.EM_DISPUTA else saved.status,
        )
        SessionRuntime(s, restored = true).also { runtimes[s.id] = it; it.start() }
    }

    // ------------------------------------------------------------------ API pública (delegada ao ator)

    override suspend fun startDispute(sessionId: String) { authorizedRuntime(sessionId)?.call { startDispute() } }

    override suspend fun recordOurBid(sessionId: String, value: Double): BidResult =
        authorizedRuntime(sessionId)?.call { ourBid(value) } ?: BidResult.Rejected("Sessão não encontrada.")

    override suspend fun recordCompetitorBid(sessionId: String, value: Double, alias: String): BidResult =
        authorizedRuntime(sessionId)?.call { competitorBid(value, alias) } ?: BidResult.Rejected("Sessão não encontrada.")

    override suspend fun setPosition(sessionId: String, position: Int) { authorizedRuntime(sessionId)?.call { setPosition(position) } }

    override suspend fun startTimer(sessionId: String, seconds: Int) { authorizedRuntime(sessionId)?.call { startTimer(seconds) } }

    override suspend fun stopTimer(sessionId: String) { authorizedRuntime(sessionId)?.call { stopTimer(byUser = true) } }

    override suspend fun finishSession(sessionId: String, won: Boolean, finalValue: Double) {
        val runtime = authorizedRuntime(sessionId) ?: return
        runtime.call { finish(won, finalValue) }
        runtime.teardown(keepState = true)
        runtimes.remove(sessionId, runtime)
        runCatching { notifier.cancelSessionAlerts(sessionId) }
    }

    override suspend fun updateRule(sessionId: String, rule: BidRule) {
        val runtime = runtimes[sessionId] ?: error("Sessão não encontrada.")
        val role = requireMember(runtime.state.companyId)
        val current = runtime.state.rule
        val normalized = rule.copy(mode = RobotMode.MANUAL, simulation = true)
        val floorChanged = current.floorPrice != normalized.floorPrice
        val othersChanged = current.copy(floorPrice = normalized.floorPrice) != normalized
        // RBAC por tipo de mudança: piso → APROVAR_PISO (Diretoria/Financeiro/Admin); demais → ALTERAR_REGRAS (Diretoria/Admin).
        if (floorChanged) check(Rbac.can(role, Permission.APROVAR_PISO)) { "Perfil sem permissão para alterar o piso (exige “${Permission.APROVAR_PISO.label}”)." }
        if (othersChanged) check(Rbac.can(role, Permission.ALTERAR_REGRAS)) { "Perfil sem permissão para alterar regras (exige “${Permission.ALTERAR_REGRAS.label}”)." }
        if (!floorChanged && !othersChanged) return
        val outcome = runtime.call { updateRule(normalized) } ?: error("Sessão não encontrada.")
        if (outcome.isNotEmpty()) error(outcome)
    }

    override suspend fun setAlertsMuted(muted: Boolean): Int {
        val company = auth.session.value?.activeCompany?.id ?: return 0
        val role = requireMember(company)
        check(Rbac.can(role, Permission.OPERAR_SESSOES) || Rbac.can(role, Permission.ALTERAR_REGRAS)) { "Perfil sem permissão para silenciar alertas." }
        this.muted.value = muted
        val affected = runtimes.values.count { it.state.companyId == company && it.state.isOpen }
        safely {
            audit.record(
                AuditAction.CONFIGURACAO, AuditResult.SUCESSO, AuditOrigin.USUARIO,
                newValue = if (muted) "Alertas silenciados" else "Alertas reativados",
                details = "Sala de Guerra: $affected sessão(ões) aberta(s) mantida(s).",
            )
        }
        return affected
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

    private inner class SessionRuntime(initial: LiveSession, private val restored: Boolean) {
        val id: String = initial.id
        @Volatile var state: LiveSession = initial
            private set

        private val mailbox = Channel<suspend () -> Unit>(Channel.UNLIMITED)
        private var actor: Job? = null
        private var timer: Job? = null
        private var ticksSincePersist = 0
        private var bidsCount = 0
        private val alerted = mutableSetOf<AlertKind>()

        private val now get() = clock()

        fun start() {
            states.update { it + (id to state) }
            actor = scope.launch {
                try {
                    open()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    critical("Falha ao abrir a sessão: ${e.message ?: e.javaClass.simpleName}")
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

        fun teardown(keepState: Boolean = false) {
            timer?.cancel()
            mailbox.close()
            if (!keepState) states.update { it - id }
        }

        private suspend fun open() {
            if (!state.isOpen) return
            // A linha da sessão precisa existir antes do primeiro evento (o store valida sessão/empresa ao gravar o log).
            persist()
            if (restored) {
                log(BidEventType.SESSION_OPENED, "Acompanhamento restaurado. Cronômetro parado; registre os lances atuais do portal para atualizar a telemetria.")
            } else {
                log(BidEventType.SESSION_OPENED, "Acompanhamento assistido iniciado por ${currentUserName()}. Lances só são enviados pelo robô de lance quando armado por você; no modo manual, opere no portal e registre aqui.", actor = currentUserName())
                auditRecord(AuditAction.CADASTRO, details = "Sessão assistida criada — piso ${Formatters.brl(state.rule.floorPrice)}, custo ${Formatters.brl(state.rule.costPrice)}")
            }
        }

        // ------------------------------------------------------------ utilitários de estado

        private fun set(transform: LiveSession.() -> LiveSession) {
            state = state.transform().copy(updatedAt = now)
            states.update { it + (id to state) }
        }

        private suspend fun persist() {
            ticksSincePersist = 0
            safely { store.saveSession(state) }
        }

        private suspend fun log(type: BidEventType, description: String, value: Double? = null, actor: String = "Sistema") {
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
            timer?.cancel()
            set { copy(status = LiveStatus.ERRO, robotStatus = RobotStatus.ERRO, lastError = message, timerRunning = false) }
            log(BidEventType.ERROR, message)
            auditRecord(AuditAction.ERRO, AuditResult.FALHA, AuditOrigin.SISTEMA, details = message)
            notify(NotificationCategory.CRITICA, "Erro na sessão assistida", "$sessionLabel: $message", critical = true)
            persist()
        }

        // ------------------------------------------------------------ operação assistida

        suspend fun startDispute() {
            if (!state.isOpen || state.status == LiveStatus.EM_DISPUTA) return
            val user = currentUserName()
            set { copy(status = LiveStatus.EM_DISPUTA) }
            log(BidEventType.POSITION_CHANGED, "Disputa iniciada no portal (informado por $user).", actor = user)
            persist()
        }

        suspend fun ourBid(value: Double): BidResult {
            if (!state.isOpen) return BidResult.Rejected("A sessão já foi encerrada.")
            val user = currentUserName()
            val blocked = AssistedBidding.validateOurBid(state.rule, value, state.captchaPending)
            if (blocked != null) {
                log(BidEventType.BID_BLOCKED, "Registro de lance recusado (${Formatters.brl(value)}): $blocked", value, actor = user)
                auditRecord(AuditAction.LANCE, AuditResult.BLOQUEADO, AuditOrigin.USUARIO, newValue = Formatters.brl(value), reason = blocked, details = "Registro assistido")
                return BidResult.Rejected(blocked)
            }
            val wasWinning = state.isWinning
            set { AssistedBidding.applyOurBid(this, value) }
            bidsCount++
            val margin = Formatters.percent(state.rule.marginPct(value))
            val floorGap = Formatters.brl(AssistedBidding.distanceToFloor(state.rule, value))
            log(BidEventType.OUR_BID, "Lance registrado por $user: ${Formatters.brl(value)} — ${state.position}º lugar, margem $margin, $floorGap acima do piso.", value, actor = user)
            if (!wasWinning && state.isWinning) log(BidEventType.POSITION_CHANGED, "Assumimos a 1ª posição.", actor = user)
            auditRecord(AuditAction.LANCE, AuditResult.SUCESSO, AuditOrigin.USUARIO, newValue = Formatters.brl(value), details = "Registro assistido (lance dado pelo usuário no portal); posição ${state.position}; margem $margin")
            persist()
            evaluateAlerts()
            return BidResult.Accepted(value, state.position)
        }

        suspend fun competitorBid(value: Double, alias: String): BidResult {
            if (!state.isOpen) return BidResult.Rejected("A sessão já foi encerrada.")
            AssistedBidding.validateCompetitorBid(value)?.let { return BidResult.Rejected(it) }
            val wasWinning = state.isWinning
            val who = alias.ifBlank { "Concorrente" }
            set { AssistedBidding.applyCompetitorBid(this, value) }
            log(BidEventType.COMPETITOR_BID, "$who ofertou ${Formatters.brl(value)} (registrado por ${currentUserName()}).", value, actor = who)
            if (wasWinning && !state.isWinning) {
                log(BidEventType.POSITION_CHANGED, "Perdemos a 1ª posição para $who.", actor = "Portal")
            }
            persist()
            evaluateAlerts()
            return BidResult.Accepted(value, state.position)
        }

        suspend fun setPosition(position: Int) {
            if (!state.isOpen) return
            val p = position.coerceIn(0, 99)
            if (p == state.position) return
            val user = currentUserName()
            val old = state.position
            set { copy(position = p, status = if (status == LiveStatus.AGUARDANDO) LiveStatus.EM_DISPUTA else status) }
            log(BidEventType.POSITION_CHANGED, "Posição informada por $user: ${if (old == 0) "—" else "${old}º"} → ${if (p == 0) "—" else "${p}º"}.", actor = user)
            persist()
            evaluateAlerts()
        }

        suspend fun startTimer(seconds: Int) {
            if (!state.isOpen) return
            val total = seconds.coerceIn(1, 24 * 3600)
            timer?.cancel()
            alerted -= AlertKind.TEMPO_CRITICO
            val user = currentUserName()
            set { copy(remainingSeconds = total, timerRunning = true, status = if (status == LiveStatus.AGUARDANDO) LiveStatus.EM_DISPUTA else status) }
            log(BidEventType.RULE_CHANGED, "Cronômetro iniciado por $user: ${Formatters.countdown(total)}.", actor = user)
            persist()
            evaluateAlerts()
            timer = scope.launch {
                while (true) {
                    delay(1_000)
                    val done = call { tick() } ?: true
                    if (done) break
                }
            }
        }

        /** @return true quando a contagem terminou. */
        private suspend fun tick(): Boolean {
            if (!state.timerRunning || !state.isOpen) return true
            val next = (state.remainingSeconds ?: 0) - 1
            if (next <= 0) {
                set { copy(remainingSeconds = 0, timerRunning = false) }
                log(BidEventType.FLOOR_REACHED, "Cronômetro zerado. Confira no portal se a disputa foi encerrada e registre o resultado.")
                if (!muted.value) notify(NotificationCategory.LANCES, "Tempo esgotado", "$sessionLabel: cronômetro zerado. Confira o portal e registre o resultado.", critical = true)
                persist()
                return true
            }
            set { copy(remainingSeconds = next) }
            if (++ticksSincePersist >= 20) persist()
            evaluateAlerts()
            return false
        }

        suspend fun stopTimer(byUser: Boolean) {
            timer?.cancel()
            timer = null
            if (!state.timerRunning) return
            set { copy(timerRunning = false) }
            if (byUser) log(BidEventType.RULE_CHANGED, "Cronômetro pausado por ${currentUserName()} em ${Formatters.countdown(state.remainingSeconds)}.", actor = currentUserName())
            persist()
        }

        /** @return "" quando aplicada; mensagem de erro quando rejeitada. */
        suspend fun updateRule(newRule: BidRule): String {
            if (!state.isOpen) return "A sessão já foi encerrada."
            val rule = newRule.copy(mode = RobotMode.MANUAL, simulation = true)
            if (!BidRuleEngine.isOperable(rule)) {
                val reason = BidRuleEngine.validateRule(rule).joinToString(" ").ifBlank { "Parâmetros inválidos." }
                log(BidEventType.ERROR, "Regra rejeitada: $reason", actor = currentUserName())
                return reason
            }
            val old = state.rule
            if (old == rule) return ""
            val user = currentUserName()
            set { copy(rule = rule) }
            if (old.floorPrice != rule.floorPrice) {
                log(BidEventType.RULE_CHANGED, "Piso alterado por $user: ${Formatters.brl(old.floorPrice)} → ${Formatters.brl(rule.floorPrice)}.", rule.floorPrice, actor = user)
                auditRecord(AuditAction.MUDANCA_PISO, previousValue = Formatters.brl(old.floorPrice), newValue = Formatters.brl(rule.floorPrice), details = "Confirmado pelo usuário na tela de parâmetros")
            }
            val changes = buildList {
                if (old.strategy != rule.strategy) add("estratégia ${old.strategy.label} → ${rule.strategy.label}")
                if (old.initialPrice != rule.initialPrice) add("preço inicial ${Formatters.brl(old.initialPrice)} → ${Formatters.brl(rule.initialPrice)}")
                if (old.costPrice != rule.costPrice) add("custo ${Formatters.brl(old.costPrice)} → ${Formatters.brl(rule.costPrice)}")
                if (old.reductionValue != rule.reductionValue) add("redução ${Formatters.brl(old.reductionValue)} → ${Formatters.brl(rule.reductionValue)}")
                if (old.minMarginPct != rule.minMarginPct) add("margem mínima ${Formatters.percent(old.minMarginPct)} → ${Formatters.percent(rule.minMarginPct)}")
                if (old.lossLimit != rule.lossLimit) add("limite de perda ${Formatters.brl(old.lossLimit)} → ${Formatters.brl(rule.lossLimit)}")
                if (old.authorizationThresholdPct != rule.authorizationThresholdPct) add("alerta de piso ${Formatters.percent(old.authorizationThresholdPct)} → ${Formatters.percent(rule.authorizationThresholdPct)}")
            }
            if (changes.isNotEmpty()) {
                log(BidEventType.RULE_CHANGED, "Parâmetros alterados por $user: ${changes.joinToString("; ")}.", actor = user)
                auditRecord(AuditAction.MUDANCA_REGRA, newValue = changes.joinToString("; "))
            }
            alerted.clear()
            persist()
            evaluateAlerts()
            return ""
        }

        suspend fun closeByUser() {
            timer?.cancel()
            val user = currentUserName()
            if (state.isOpen) {
                set { copy(status = LiveStatus.ENCERRADA, robotStatus = RobotStatus.ENCERRADO, timerRunning = false) }
                log(BidEventType.SESSION_CLOSED, "Acompanhamento encerrado por $user sem resultado informado.", actor = user)
                auditRecord(AuditAction.CONTROLE_MANUAL, details = "Acompanhamento encerrado sem resultado")
            }
            persist()
        }

        suspend fun finish(won: Boolean, finalValue: Double) {
            if (!state.isOpen) return
            timer?.cancel()
            val user = currentUserName()
            val value = finalValue.takeIf { !it.isNaN() && !it.isInfinite() && it > 0.0 } ?: (state.ourLastBid ?: state.rule.initialPrice)
            set {
                copy(
                    status = LiveStatus.ENCERRADA, robotStatus = RobotStatus.ENCERRADO, timerRunning = false,
                    bestBid = value, position = if (won) 1 else maxOf(position, 2), ourLastBid = if (won) value else ourLastBid,
                )
            }
            val segment = auth.session.value?.activeCompany?.segment ?: Segment.PERSONALIZADO
            val record = AssistedBidding.closingRecord(state, won, value, bidsCount, segment, now)
            var recorded = false
            safely { competition.insert(record); recorded = true }
            var tenderUpdated = false
            state.tenderId?.let { tenderId ->
                safely { tenders.updateStatus(tenderId, if (won) TenderStatus.VENCIDA else TenderStatus.PERDIDA); tenderUpdated = true }
            }
            val result = if (won) "VENCEMOS com ${Formatters.brl(value)}" else "PERDEMOS — lance vencedor ${Formatters.brl(value)}"
            log(
                BidEventType.SESSION_CLOSED,
                "Disputa encerrada ($result), informado por $user. Margem final ${Formatters.percent(record.ourMarginPct)}." +
                    (if (recorded) " Registro de concorrência criado." else " Falha ao gravar concorrência.") +
                    (if (state.tenderId != null) if (tenderUpdated) " Licitação marcada como ${if (won) "VENCIDA" else "PERDIDA"}." else " Falha ao atualizar a licitação." else ""),
                value, actor = user,
            )
            auditRecord(
                AuditAction.CONTROLE_MANUAL, newValue = result,
                details = "Encerramento assistido; ${bidsCount} lance(s) nosso(s) registrado(s); concorrência ${if (recorded) "gravada" else "não gravada"}",
            )
            notify(NotificationCategory.SESSOES, if (won) "Disputa encerrada — vencemos!" else "Disputa encerrada", "$sessionLabel: $result.", route = Routes.COMPETITION)
            persist()
        }

        // ------------------------------------------------------------ alertas

        private suspend fun evaluateAlerts() {
            val active = AssistedBidding.activeAlerts(state)
            alerted.retainAll(active)
            if (muted.value) return
            val fresh = active - alerted
            for (kind in fresh) {
                alerted += kind
                val ours = state.ourLastBid
                when (kind) {
                    AlertKind.MARGEM_ABAIXO_MINIMA -> notify(
                        NotificationCategory.LANCES, "Margem abaixo da mínima",
                        "$sessionLabel: margem ${Formatters.percent(state.currentMarginPct)} (mínima ${Formatters.percent(state.rule.minMarginPct)}).",
                    )
                    AlertKind.PROXIMO_DO_PISO -> notify(
                        NotificationCategory.LANCES, "Lance a ${Formatters.percent(state.rule.authorizationThresholdPct, 0)} do piso",
                        "$sessionLabel: ${Formatters.brl(ours)} está a ${Formatters.brl(ours?.let { AssistedBidding.distanceToFloor(state.rule, it) })} do piso ${Formatters.brl(BidRuleEngine.effectiveFloor(state.rule))}.",
                        critical = true,
                    )
                    AlertKind.TEMPO_CRITICO -> notify(
                        NotificationCategory.LANCES, "Fechamento iminente",
                        "$sessionLabel: menos de ${AssistedBidding.TIMER_ALERT_SECONDS} s no cronômetro.", critical = true,
                    )
                    AlertKind.PERDEMOS_POSICAO -> notify(
                        NotificationCategory.LANCES, "Não estamos em 1º",
                        "$sessionLabel: posição ${state.position}º. Melhor lance ${Formatters.brl(state.bestBid)}.",
                    )
                }
            }
        }
    }
}
