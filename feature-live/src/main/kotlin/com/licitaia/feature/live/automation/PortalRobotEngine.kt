package com.licitaia.feature.live.automation

import com.licitaia.core.ui.nav.Routes
import com.licitaia.domain.bidding.BidRuleEngine
import com.licitaia.domain.live.LiveSessionManager
import com.licitaia.domain.model.AuctioneerMessage
import com.licitaia.domain.model.AuditAction
import com.licitaia.domain.model.AuditOrigin
import com.licitaia.domain.model.AuditResult
import com.licitaia.domain.model.BidResult
import com.licitaia.domain.model.LiveSessionSpec
import com.licitaia.domain.model.LiveStatus
import com.licitaia.domain.model.NotificationCategory
import com.licitaia.domain.model.Portal
import com.licitaia.domain.portal.BidRobotMode
import com.licitaia.domain.portal.PortalMyTender
import com.licitaia.domain.portal.PortalRobotPlan
import com.licitaia.domain.portal.PortalRobotRepository
import com.licitaia.domain.portal.ProposalItemPlan
import com.licitaia.domain.portal.RobotProposalStatus
import com.licitaia.domain.repository.AppNotifier
import com.licitaia.domain.repository.AuditRepository
import com.licitaia.domain.repository.AuthRepository
import com.licitaia.domain.repository.MessageRepository
import com.licitaia.domain.security.Permission
import com.licitaia.domain.security.Rbac
import com.licitaia.domain.util.Formatters
import com.licitaia.feature.live.web.PortalWebViewHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Mantém o processo vivo (serviço em primeiro plano) enquanto houver robô rodando. Implementado em feature-bidding. */
interface PortalRobotForeground {
    fun updateRobots(active: Int)

    object None : PortalRobotForeground { override fun updateRobots(active: Int) = Unit }
}

enum class RobotKind(val label: String) { PROPOSTA("Robô de proposta"), LANCE("Robô de lance") }

enum class RunStatus(val label: String) {
    EXECUTANDO("Executando"),
    AGUARDANDO_USUARIO("Aguardando você"),
    PAUSADO("Pausado (CAPTCHA/código)"),
    PARADO("Parado"),
    CONCLUIDO("Concluído"),
    FALHOU("Falhou"),
}

data class BidSuggestion(val itemNumber: Int, val value: Double, val reason: String)

/** Por que o robô está esperando o usuário (muda a faixa sobre o portal). */
enum class RobotAttention {
    /** Termo/declarações legais pendentes: o USUÁRIO marca e toca "Continuar" (o robô nunca marca). */
    DECLARATIONS,
    /** O app precisa estar aberto com a tela ligada (sem janela o portal não carrega). */
    OPEN_APP,
}

/** Estado de UMA execução de robô (memória; o histórico durável fica no plano, nos eventos de lance e na auditoria). */
data class RobotRun(
    val id: String,
    val companyId: Long,
    val tenderKey: String,
    val title: String,
    val kind: RobotKind,
    val status: RunStatus,
    val step: String,
    val message: String? = null,
    val log: List<String> = emptyList(),
    val suggestion: BidSuggestion? = null,
    val bidsSent: Int = 0,
    val startedAt: Long,
    val updatedAt: Long,
    /** Motivo da espera pelo usuário (null = genérico: "Tentar de novo"). */
    val attention: RobotAttention? = null,
) {
    val active: Boolean get() = status == RunStatus.EXECUTANDO || status == RunStatus.AGUARDANDO_USUARIO || status == RunStatus.PAUSADO
    val needsUser: Boolean get() = status == RunStatus.AGUARDANDO_USUARIO || status == RunStatus.PAUSADO
}

/**
 * Robôs do Compras.gov.br na aba RETIDA e logada do usuário (DOM, como o usuário faria):
 * - PROPOSTA: abre Compras eletrônicas pelo menu, procura a compra ("Minhas participações" ou "Todas as compras" com
 *   UASG + número DIGITADOS), abre o cadastro de proposta (`cadastro-propostas?compra=<código>` conferido), verifica o
 *   termo/declarações (NUNCA marca: se faltar, para e mostra o portal ao usuário) e preenche/salva item a item
 *   ([SpaNavigator]), conferindo o aviso de sucesso e o "Meu valor (unitário)" do cartão. Nunca clica na lixeira.
 * - LANCE: lê a sala de disputa, decide com [AutoBidDecider] (estratégias e configuração da tela Estratégias, piso, 20 s/3 s, teto) e,
 *   no modo AUTOMÁTICO armado pelo usuário, preenche e envia; no MANUAL só sugere (o usuário toca Enviar). Lê o chat do
 *   pregoeiro e grava em "Mensagens do Pregoeiro". Registra lances no histórico de Pregões ao Vivo/Sala de Guerra.
 * Qualquer passo não encontrado PARA e pede o usuário (tela do portal visível com "Mapear esta tela").
 *
 * Lista de compras e cadastro de proposta: seletores REAIS mapeados no aparelho ([SpaScripts]). Sala de disputa e chat:
 * ainda HEURÍSTICA por texto ([PortalTargets], [BidRoomParser]). O robô opera na aba retida ANEXADA à janela do app:
 * com o app fechado/tela apagada ele para pedindo "abra o app".
 */
@Singleton
class PortalRobotEngine @Inject constructor(
    private val gate: PortalSessionGate,
    private val holder: PortalWebViewHolder,
    private val storage: PortalAutomationStorage,
    private val repo: PortalRobotRepository,
    private val live: LiveSessionManager,
    private val messages: MessageRepository,
    private val notifier: AppNotifier,
    private val audit: AuditRepository,
    private val auth: AuthRepository,
    private val foreground: PortalRobotForeground,
    /** Configuração padrão da tela Estratégias (decremento mínimo em R$ ou %, intervalo entre lances). */
    private val strategies: com.licitaia.domain.bidding.BidStrategyConfigRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _runs = MutableStateFlow<Map<String, RobotRun>>(emptyMap())
    val runs: StateFlow<Map<String, RobotRun>> = _runs.asStateFlow()

    private val _portalRequests = MutableSharedFlow<Long>(extraBufferCapacity = 4)
    /** O robô precisa que o usuário VEJA o portal (ex.: marcar termo/declarações): a Activity abre a tela do portal. */
    val portalRequests: SharedFlow<Long> = _portalRequests.asSharedFlow()

    private class Control {
        @Volatile var stop = false
        @Volatile var resume: CompletableDeferred<Boolean>? = null
        @Volatile var pendingSend: BidSuggestion? = null
        var job: Job? = null
    }

    private val controls = ConcurrentHashMap<String, Control>()

    fun runsFor(companyId: Long, tenderKey: String): List<RobotRun> = _runs.value.values.filter { it.companyId == companyId && it.tenderKey == tenderKey }

    fun activeRun(companyId: Long, tenderKey: String, kind: RobotKind): RobotRun? =
        _runs.value.values.firstOrNull { it.companyId == companyId && it.tenderKey == tenderKey && it.kind == kind && it.active }

    // ------------------------------------------------------------------ comandos (UI / agenda / notificação)

    /** "Soltar robô — cadastrar proposta" (a UI já mostrou a confirmação explícita). */
    suspend fun startProposal(companyId: Long, tenderKey: String): Result<String> = runCatching {
        requireOperator(companyId)
        activeRun(companyId, tenderKey, RobotKind.PROPOSTA)?.let { error("O robô de proposta já está rodando para esta licitação.") }
        val tender = myTender(companyId, tenderKey)
        val plan = repo.getPlan(companyId, tenderKey) ?: error("Configure os itens da proposta antes.")
        RobotPlanRules.proposalErrors(plan.items).firstOrNull()?.let { error(it) }
        launchRun(companyId, tender, RobotKind.PROPOSTA) { ctrl, id -> proposalRun(ctrl, id, companyId, tender, plan) }
    }

    /** Inicia o robô de lance (agenda ou "Entrar na disputa agora"). Exige o plano ARMADO pelo usuário. */
    suspend fun startBid(companyId: Long, tenderKey: String): Result<String> = runCatching {
        requireOperator(companyId)
        activeRun(companyId, tenderKey, RobotKind.LANCE)?.let { return@runCatching it.id }
        val tender = myTender(companyId, tenderKey)
        val plan = repo.getPlan(companyId, tenderKey) ?: error("Configure o robô de lance antes.")
        check(plan.bidArmed) { "O robô de lance não está armado para esta licitação." }
        RobotPlanRules.bidErrors(plan).firstOrNull()?.let { error(it) }
        launchRun(companyId, tender, RobotKind.LANCE) { ctrl, id -> bidRun(ctrl, id, companyId, tender, plan) }
    }

    /**
     * Ao armar o robô de lance: cria (ou reaproveita) a sessão de Pregões ao Vivo de cada item com piso, para a
     * licitação aparecer em Pregões ao Vivo e na Sala de Guerra como "Aguardando abertura" antes da disputa.
     */
    suspend fun ensureLiveSessions(companyId: Long, tenderKey: String): Int {
        requireOperator(companyId)
        val tender = myTender(companyId, tenderKey)
        val plan = repo.getPlan(companyId, tenderKey) ?: return 0
        return plan.items.filter { it.floorUnitPrice != null }.count { findOrOpenSession(companyId, tender, it, plan) != null }
    }

    /** "Tentar de novo"/"Continuar" depois de o usuário resolver no portal. */
    fun continueRun(runId: String) { controls[runId]?.resume?.complete(true) }

    /** "Continuar manualmente": o robô para e o usuário segue no portal. */
    fun takeOverManually(runId: String) {
        controls[runId]?.let { it.stop = true; it.resume?.complete(false) }
        log(runId, "Você assumiu o controle manualmente.")
        setStatus(runId, RunStatus.PARADO, "Continuar manualmente no portal.")
    }

    fun stop(runId: String, reason: String = "Parado pelo usuário.") {
        val c = controls[runId] ?: return
        c.stop = true
        c.resume?.complete(false)
        log(runId, reason)
        setStatus(runId, RunStatus.PARADO, reason)
    }

    /** PARAR geral (botão grande e ação da notificação). */
    fun stopAll(reason: String = "PARAR acionado.") {
        _runs.value.values.filter { it.active }.forEach { stop(it.id, reason) }
    }

    /** Modo manual: o usuário toca "Enviar" na sugestão atual. O envio passa pelas mesmas travas (piso/intervalos). */
    fun sendSuggestion(runId: String) {
        val run = _runs.value[runId] ?: return
        controls[runId]?.pendingSend = run.suggestion
    }

    /** "Mapear esta tela" da aba retida do Comprasnet. */
    suspend fun mapCurrentScreen(companyId: Long): Boolean = withContext(Dispatchers.Main) {
        val entry = holder.peek(companyId, Portal.COMPRAS_GOV) ?: return@withContext false
        val done = CompletableDeferred<Boolean>()
        holder.captureSnapshot(entry, "manual") { done.complete(it) }
        withTimeoutOrNull(10_000) { done.await() } ?: false
    }

    // ------------------------------------------------------------------ infraestrutura das execuções

    private fun requireOperator(companyId: Long) {
        val s = auth.session.value ?: error("Entre no LicitaIA.")
        check(!s.user.demo && s.activeCompany.id == companyId && companyId in s.user.companyIds) { "Empresa não autorizada." }
        check(Rbac.can(s.user.role, Permission.OPERAR_SESSOES)) { "Seu perfil não pode operar robôs." }
    }

    private suspend fun myTender(companyId: Long, key: String): PortalMyTender =
        repo.getMyTenders(companyId).firstOrNull { it.tenderKey == key } ?: error("Licitação não encontrada em Minhas licitações.")

    private fun launchRun(companyId: Long, tender: PortalMyTender, kind: RobotKind, body: suspend (Control, String) -> Unit): String {
        val id = "rb-" + UUID.randomUUID().toString().take(8)
        val now = System.currentTimeMillis()
        val ctrl = Control()
        controls[id] = ctrl
        _runs.update { it + (id to RobotRun(id, companyId, tender.tenderKey, tender.label, kind, RunStatus.EXECUTANDO, "Iniciando…", startedAt = now, updatedAt = now)) }
        publishForeground()
        ctrl.job = scope.launch {
            try {
                body(ctrl, id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log(id, "Erro interno: ${e.message ?: e.javaClass.simpleName}")
                setStatus(id, RunStatus.FALHOU, e.message ?: "Erro interno.")
            } finally {
                _runs.value[id]?.takeIf { it.active }?.let { setStatus(id, if (ctrl.stop) RunStatus.PARADO else RunStatus.CONCLUIDO, it.message) }
                controls.remove(id)
                publishForeground()
            }
        }
        return id
    }

    private fun publishForeground() {
        runCatching { foreground.updateRobots(_runs.value.values.count { it.active }) }
    }

    private fun log(runId: String, line: String) {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())
        _runs.update { m -> m[runId]?.let { r -> m + (runId to r.copy(log = (r.log + "$stamp $line").takeLast(300), updatedAt = System.currentTimeMillis())) } ?: m }
    }

    private fun setStatus(runId: String, status: RunStatus, message: String? = null, step: String? = null, attention: RobotAttention? = null) {
        _runs.update { m ->
            m[runId]?.let { r -> m + (runId to r.copy(status = status, message = message, step = step ?: r.step, attention = attention, updatedAt = System.currentTimeMillis())) } ?: m
        }
        publishForeground()
    }

    private fun setStep(runId: String, step: String) {
        _runs.update { m -> m[runId]?.let { r -> m + (runId to r.copy(step = step, status = RunStatus.EXECUTANDO, message = null, attention = null, updatedAt = System.currentTimeMillis())) } ?: m }
        log(runId, step)
    }

    /**
     * Para e espera o usuário (tela do portal + banner com "Tentar de novo", "Mapear esta tela", "Continuar
     * manualmente"). true = tentar/continuar; false = parar.
     */
    private suspend fun awaitUser(
        ctrl: Control, runId: String, message: String, captcha: Boolean = false,
        attention: RobotAttention? = null, showPortal: Boolean = false,
    ): Boolean {
        if (ctrl.stop) return false
        val d = CompletableDeferred<Boolean>()
        ctrl.resume = d
        log(runId, "Aguardando você: $message")
        setStatus(runId, if (captcha) RunStatus.PAUSADO else RunStatus.AGUARDANDO_USUARIO, message, attention = attention)
        val run = _runs.value[runId]
        // Mostra a tela do portal (com a faixa do robô) quando o usuário precisa agir nela.
        if (showPortal) run?.companyId?.let { _portalRequests.tryEmit(it) }
        safely {
            notifier.notify(
                if (captcha) NotificationCategory.CAPTCHA else NotificationCategory.CRITICA,
                when {
                    captcha -> "Robô pausado: resolva no portal"
                    attention == RobotAttention.DECLARATIONS -> "Robô parado: termo e declarações"
                    attention == RobotAttention.OPEN_APP -> "Robô parado: abra o LicitaIA"
                    else -> "Robô parado: precisa de você"
                },
                "${run?.title.orEmpty()}: $message",
                critical = true, route = Routes.portalWeb(Portal.COMPRAS_GOV), companyId = run?.companyId,
            )
        }
        val ok = d.await()
        ctrl.resume = null
        if (ok && !ctrl.stop) setStatus(runId, RunStatus.EXECUTANDO, null)
        return ok && !ctrl.stop
    }

    /** Executa um passo; se falhar, para e pede o usuário até ele mandar tentar de novo ou parar. */
    private suspend fun step(ctrl: Control, runId: String, label: String, block: suspend () -> StepOutcome): Boolean {
        setStep(runId, label)
        while (true) {
            if (ctrl.stop) return false
            val o = block()
            if (o.ok) return true
            log(runId, "Passo “$label”: ${o.describe()}")
            val captcha = o is StepOutcome.Blocked && (o.state == PageState.CAPTCHA || o.state == PageState.MFA)
            val openApp = o is StepOutcome.Failed && o.target.key == APP_NOT_VISIBLE_KEY
            val msg = when {
                openApp -> PortalSessionGate.OPEN_APP_MESSAGE + " Depois toque em Tentar de novo."
                captcha -> "Parei em “$label”: ${o.describe()}. Resolva no portal e toque em Tentar de novo."
                else -> "Parei em “$label”: ${o.describe()}. Ajuste no portal (ou use Mapear esta tela) e toque em Tentar de novo."
            }
            if (!awaitUser(ctrl, runId, msg, captcha, attention = if (openApp) RobotAttention.OPEN_APP else null)) return false
        }
    }

    /** null = ok; senão o motivo vira um passo falho (para e pede o usuário). */
    private fun spaOutcome(label: String, reason: String?): StepOutcome =
        if (reason == null) StepOutcome.Ok(FindResult(true)) else StepOutcome.Failed(Target("spa", label, ElementKind.ANY, emptyList()), reason)

    private fun runner(companyId: Long) = PortalAutomationRunner(gate.driver(companyId), storage.learned, onLearned = { storage.saveLearned() })

    private fun gateOutcome(r: PortalSessionGate.Result, label: String): StepOutcome = when (r) {
        PortalSessionGate.Result.Electronic -> StepOutcome.Ok(FindResult(true))
        PortalSessionGate.Result.Workspace -> StepOutcome.Failed(Target("gate", label, ElementKind.ANY, emptyList()), "ainda na área de trabalho")
        is PortalSessionGate.Result.NotLogged -> StepOutcome.Failed(Target("gate", label, ElementKind.ANY, emptyList()), r.reason)
        is PortalSessionGate.Result.Failed -> StepOutcome.Failed(Target("gate", label, ElementKind.ANY, emptyList()), r.reason)
        PortalSessionGate.Result.AppNotVisible -> StepOutcome.Failed(Target(APP_NOT_VISIBLE_KEY, label, ElementKind.ANY, emptyList()), PortalSessionGate.OPEN_APP_MESSAGE)
    }

    private fun purchaseOf(t: PortalMyTender) = SpaNavigator.Purchase(t.uasg, t.modality, t.number, t.year)

    /** Procura a compra (lista atual ou "Todas as compras" com UASG + número digitados) e abre o cadastro de proposta. */
    private suspend fun openPurchase(companyId: Long, nav: SpaNavigator, t: PortalMyTender): StepOutcome {
        if (!gate.ensureLive(companyId)) return gateOutcome(PortalSessionGate.Result.AppNotVisible, "Abrir a compra")
        return spaOutcome("Abrir a compra", nav.openPurchase(purchaseOf(t)))
    }

    private suspend inline fun safely(block: () -> Unit) {
        try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
    }

    private suspend fun savePlan(companyId: Long, key: String, transform: (PortalRobotPlan) -> PortalRobotPlan) {
        safely { repo.getPlan(companyId, key)?.let { repo.savePlan(transform(it)) } }
    }

    private fun userName() = auth.session.value?.user?.name ?: "Operador"

    // ================================================================== robô de PROPOSTA

    private suspend fun proposalRun(ctrl: Control, runId: String, companyId: Long, t: PortalMyTender, plan: PortalRobotPlan) {
        val lines = mutableListOf<String>()
        fun record(line: String) { lines += line; log(runId, line) }
        val nav = SpaNavigator(gate.driver(companyId), log = { log(runId, it) })
        val purchase = purchaseOf(t)
        record("Robô de proposta solto por ${userName()}: ${plan.items.size} item(ns), total ${Formatters.brl(plan.items.sumOf { it.totalPrice })}.")
        savePlan(companyId, t.tenderKey) { it.copy(proposalStatus = RobotProposalStatus.EXECUTANDO) }
        safely { audit.record(AuditAction.ROBO_ATIVADO, origin = AuditOrigin.USUARIO, portal = Portal.COMPRAS_GOV, tenderNumber = "${t.number}/${t.year}", details = "Robô de proposta: ${plan.items.size} item(ns)") }

        var okCount = 0
        val finish: suspend (RobotProposalStatus, String) -> Unit = { status, msg ->
            record(msg)
            savePlan(companyId, t.tenderKey) { it.copy(proposalStatus = status, proposalLog = lines.toList()) }
            safely { notifier.notify(NotificationCategory.SESSOES, "Robô de proposta: ${status.label}", "${t.label}: $msg", route = null, companyId = companyId) }
        }
        val owner = "o robô de proposta"
        gate.claimTab(owner)?.let { other ->
            finish(RobotProposalStatus.AGUARDANDO_USUARIO, "A aba do Comprasnet está em uso ($other). Tente de novo quando terminar.")
            setStatus(runId, RunStatus.PARADO, lines.lastOrNull())
            return
        }
        try {
            if (!step(ctrl, runId, "Abrir Compras eletrônicas pelo menu") { gateOutcome(gate.ensureElectronic(companyId), "Compras eletrônicas") }) {
                finish(RobotProposalStatus.AGUARDANDO_USUARIO, "Parado antes de abrir Compras eletrônicas."); return
            }
            if (!step(ctrl, runId, "Abrir a compra ${t.label}") { openPurchase(companyId, nav, t) }) {
                finish(RobotProposalStatus.AGUARDANDO_USUARIO, "Parado ao abrir a compra."); return
            }
            record("Cadastro de proposta aberto (compra=${CompraCode.of(t.uasg, t.modality, t.number, t.year) ?: "${t.uasg}…${t.number}/${t.year}"}).")
            if (!checkDeclarations(ctrl, runId, nav, ::record)) {
                finish(RobotProposalStatus.AGUARDANDO_USUARIO, "Parado aguardando o termo/declarações."); return
            }
            for (item in plan.items.sortedBy { it.itemNumber }) {
                if (ctrl.stop) break
                var done = false
                while (!done && !ctrl.stop) {
                    setStep(runId, "Item ${item.itemNumber}: preenchendo")
                    val result = proposalItem(companyId, nav, purchase, item)
                    when {
                        result == null -> {
                            okCount++
                            record("Item ${item.itemNumber}: OK — ${Formatters.brl(item.unitPrice)} (unitário) salvo; o portal confirmou e o cartão mostra “Meu valor (unitário)” igual.")
                            done = true
                        }
                        result.startsWith(ALREADY_SAVED) -> {
                            okCount++
                            record("Item ${item.itemNumber}: já estava com ${Formatters.brl(item.unitPrice)} no portal — nada a alterar.")
                            done = true
                        }
                        else -> {
                            record("Item ${item.itemNumber}: PARADO — $result.")
                            if (!awaitUser(ctrl, runId, "Item ${item.itemNumber}: $result. Confira no portal e toque em Tentar de novo (ou Continuar manualmente).", showPortal = true)) break
                        }
                    }
                }
                if (!done) break
            }
        } finally {
            gate.releaseTab(owner)
        }
        val total = plan.items.size
        when {
            okCount == total -> {
                finish(RobotProposalStatus.CADASTRADA, "Proposta cadastrada: $okCount de $total item(ns) salvos e conferidos. Confira no portal antes do prazo.")
                safely { repo.upsertMyTenders(companyId, listOf(t.copy(hasProposal = true, updatedAt = System.currentTimeMillis()))) }
            }
            okCount > 0 -> finish(RobotProposalStatus.PARCIAL, "Proposta em parte: $okCount de $total item(ns) salvos.")
            else -> finish(RobotProposalStatus.FALHOU, "Nenhum item foi salvo.")
        }
        safely {
            audit.record(
                AuditAction.ENVIO, if (okCount == total) AuditResult.SUCESSO else AuditResult.FALHA, AuditOrigin.ROBO, portal = Portal.COMPRAS_GOV,
                tenderNumber = "${t.number}/${t.year}", details = "Robô de proposta: $okCount/$total item(ns) salvos",
            )
        }
        setStatus(runId, if (okCount == total) RunStatus.CONCLUIDO else if (ctrl.stop) RunStatus.PARADO else RunStatus.FALHOU, lines.lastOrNull())
    }

    /**
     * Termo/declarações (LEGAIS): o robô só LÊ. Faltando algo, para, abre a tela do portal com a faixa "Marque o termo
     * e as declarações e toque em Continuar" e confere de novo depois do toque. false = parado pelo usuário.
     */
    private suspend fun checkDeclarations(ctrl: Control, runId: String, nav: SpaNavigator, record: (String) -> Unit): Boolean {
        setStep(runId, "Conferir termo e declarações (o robô não marca)")
        var confirmed = false
        while (!ctrl.stop) {
            val state = nav.declarations()
            if (state != null && DeclarationsCheck.canProceed(state, confirmed)) {
                record(
                    if (state.section) "Termo e declarações conferidos: preenchidos por você." else "A página não mostra termo/declarações; seguindo após a sua confirmação.",
                )
                return true
            }
            val missing = state?.let(DeclarationsCheck::missing) ?: listOf("não consegui ler o bloco Termo/declarações")
            record("Termo/declarações pendentes: ${missing.joinToString("; ")}.")
            val msg = "Marque o termo e as declarações e toque em Continuar. Falta: ${missing.joinToString("; ")}. (São declarações legais da empresa: o robô nunca marca.)"
            if (!awaitUser(ctrl, runId, msg, attention = RobotAttention.DECLARATIONS, showPortal = true)) return false
            confirmed = true
        }
        return false
    }

    /** Preenche e salva UM item. null = OK; [ALREADY_SAVED] = já tinha o valor; senão o motivo (para). */
    private suspend fun proposalItem(companyId: Long, nav: SpaNavigator, p: SpaNavigator.Purchase, item: ProposalItemPlan): String? {
        if (!gate.ensureLive(companyId)) return PortalSessionGate.OPEN_APP_MESSAGE
        // Ainda na página da compra certa? (o portal pode ter navegado)
        nav.openPurchase(p)?.let { return "a página da compra não está aberta ($it)" }
        val n = item.itemNumber
        val card = nav.itemCard(n) ?: return "o item $n não aparece no cadastro de proposta desta compra"
        if (card.myUnitValue != null && ProposalMoney.matches(item.unitPrice, card.myUnitValue.toPlainString().replace('.', ','))) return ALREADY_SAVED
        nav.openItemForm(n)?.let { return it }
        nav.fillOptional(n, "quantidade ofertada", TextNorm.formatInputNumber(item.quantity), numeric = true)?.let { return it }
        nav.fillItemValue(n, item.unitPrice)?.let { return it }
        nav.fillOptional(n, "marca", item.brand, numeric = false)?.let { return it }
        nav.fillOptional(n, "fabricante", item.manufacturer, numeric = false)?.let { return it }
        nav.fillOptional(n, "modelo", item.modelVersion, numeric = false)?.let { return it }
        nav.fillOptional(n, "descricao detalhada", item.detailedDescription, numeric = false)?.let { return it }
        // Valor ainda certo logo antes de salvar (nada mexeu no campo).
        return nav.saveItem(n, item.unitPrice)
    }

    // ================================================================== robô de LANCE

    private class ItemTrack(val item: ProposalItemPlan) {
        var sessionId: String? = null
        var lastBest: Double? = null
        var bestChangedAt: Long? = null
        var lastOwnBidAt: Long? = null
        var bidsSent = 0
        var finished = false
        var startedDispute = false
    }

    private suspend fun bidRun(ctrl: Control, runId: String, companyId: Long, t: PortalMyTender, plan: PortalRobotPlan) {
        val owner = "o robô de lance"
        // A aba é uma só: espera a busca/robô de proposta terminar (até 2 min) antes de entrar na disputa.
        var other = gate.claimTab(owner)
        var waited = 0
        while (other != null && waited < 120 && !ctrl.stop) { delay(1_000); waited++; other = gate.claimTab(owner) }
        if (other != null) {
            log(runId, "A aba do Comprasnet está em uso ($other).")
            setStatus(runId, RunStatus.PARADO, "A aba do Comprasnet está em uso ($other).")
            return
        }
        try { bidRunInner(ctrl, runId, companyId, t, plan) } finally { gate.releaseTab(owner) }
    }

    private suspend fun bidRunInner(ctrl: Control, runId: String, companyId: Long, t: PortalMyTender, plan: PortalRobotPlan) {
        val runner = runner(companyId)
        val driver = gate.driver(companyId)
        val nav = SpaNavigator(driver, log = { log(runId, it) })
        val config = plan.bid
        log(runId, "Robô de lance (${config.mode.label}) iniciado. Estratégia ${config.strategy.label}; nunca abaixo do piso.")
        safely { audit.record(AuditAction.ROBO_ATIVADO, origin = AuditOrigin.USUARIO, portal = Portal.COMPRAS_GOV, tenderNumber = "${t.number}/${t.year}", details = "Robô de lance ${config.mode.label} armado em ${Formatters.dateTime(plan.bidArmedAt)}") }

        if (!step(ctrl, runId, "Abrir Compras eletrônicas pelo menu") { gateOutcome(gate.ensureElectronic(companyId), "Compras eletrônicas") }) return
        if (!step(ctrl, runId, "Abrir a compra ${t.label}") { openPurchase(companyId, nav, t) }) return
        if (!runner.click(PortalTargets.disputeRoomEntry.copy(timeoutMs = 8_000)).ok) {
            if (!awaitUser(ctrl, runId, "Não achei a sala de disputa. Abra-a no portal e toque em Continuar.")) return
        }
        val tracks = plan.items.filter { it.floorUnitPrice != null }.map { ItemTrack(it) }
        tracks.forEach { tr -> tr.sessionId = findOrOpenSession(companyId, t, tr.item, plan) }
        val enteredAt = System.currentTimeMillis()
        val strategyCfg = runCatching { strategies.get(companyId) }.getOrNull()
        val seenChat = HashSet<String>()
        safely { notifier.notify(NotificationCategory.SESSOES, "Disputa: robô na sala", "${t.label}: ${config.mode.label}. Toque em PARAR a qualquer momento.", route = Routes.LIVE, companyId = companyId) }

        loop@ while (!ctrl.stop) {
            val (state, _) = runner.pageState()
            val rows = driver.rows()
            val room = BidRoomParser.parse(rows)
            readChat(companyId, t, rows, seenChat, tracks.firstOrNull()?.sessionId)
            val now = System.currentTimeMillis()
            for (tr in tracks) {
                if (tr.finished || ctrl.stop) continue
                val ri = room.firstOrNull { it.itemNumber == tr.item.itemNumber }
                if (ri == null && state == PageState.OK && now - enteredAt < ROOM_GRACE_MS) continue
                if (ri?.bestBid != null && ri.bestBid != tr.lastBest) {
                    tr.lastBest = ri.bestBid
                    tr.bestChangedAt = now
                    if (ri.ourBid == null || kotlin.math.abs(ri.ourBid - ri.bestBid) > 0.004) {
                        tr.sessionId?.let { sid -> safely { live.recordCompetitorBid(sid, ri.bestBid, "Melhor lance (portal)") } }
                    }
                }
                if (ri != null && !tr.startedDispute && (ri.phase == DisputePhase.OPEN || ri.phase == DisputePhase.RANDOM)) {
                    tr.startedDispute = true
                    tr.sessionId?.let { sid -> safely { live.startDispute(sid) } }
                    log(runId, "Item ${tr.item.itemNumber}: fase de lances aberta.")
                }
                // Estratégias da empresa: decremento mínimo (R$ ou % do lance de referência) e intervalo entre lances valem como piso dos parâmetros da licitação.
                val reference = ri?.bestBid ?: ri?.ourBid ?: RobotPlanRules.ruleFor(tr.item, config).initialPrice
                val itemConfig = config.copy(
                    minDecrement = maxOf(config.minDecrement, strategyCfg?.decrementFor(reference) ?: 0.0),
                    ownIntervalSeconds = maxOf(config.ownIntervalSeconds, strategyCfg?.minIntervalSeconds ?: 0),
                )
                val rule = RobotPlanRules.ruleFor(tr.item, itemConfig)
                val decision = AutoBidDecider.decide(
                    AutoBidDecider.Input(itemConfig, rule, state, ri, now, tr.lastOwnBidAt, tr.bestChangedAt, tr.bidsSent),
                )
                when (decision) {
                    is AutoBidDecider.Decision.Send -> sendBid(ctrl, runId, runner, driver, t, tr, decision.value, rule, ri?.bestBid, byUser = false)
                    is AutoBidDecider.Decision.Suggest -> {
                        val s = BidSuggestion(tr.item.itemNumber, decision.value, decision.reason)
                        _runs.update { m -> m[runId]?.let { r -> m + (runId to r.copy(suggestion = s, updatedAt = now)) } ?: m }
                        val pending = ctrl.pendingSend
                        if (pending != null && pending.itemNumber == s.itemNumber) {
                            ctrl.pendingSend = null
                            // Só envia se a sugestão que o usuário viu ainda é a atual (valor não mudou).
                            if (kotlin.math.abs(pending.value - s.value) < 0.004) sendBid(ctrl, runId, runner, driver, t, tr, s.value, rule, ri?.bestBid, byUser = true)
                            else log(runId, "Item ${s.itemNumber}: a sugestão mudou antes do envio; confira o novo valor.")
                        }
                    }
                    is AutoBidDecider.Decision.Wait -> Unit
                    is AutoBidDecider.Decision.Pause -> {
                        log(runId, decision.reason)
                        if (!awaitUser(ctrl, runId, decision.reason, captcha = true)) break@loop
                    }
                    is AutoBidDecider.Decision.Stop -> {
                        if (decision.finished) {
                            tr.finished = true
                            log(runId, decision.reason)
                            finishItemSession(tr, ri)
                        } else {
                            log(runId, "PARADO: ${decision.reason}")
                            safely { notifier.notify(NotificationCategory.CRITICA, "Robô de lance parado", "${t.label}: ${decision.reason}", critical = true, route = Routes.portalWeb(Portal.COMPRAS_GOV), companyId = companyId) }
                            safely { audit.record(AuditAction.ROBO_ENCERRADO, AuditResult.BLOQUEADO, AuditOrigin.ROBO, portal = Portal.COMPRAS_GOV, tenderNumber = "${t.number}/${t.year}", reason = decision.reason) }
                            ctrl.stop = true
                            setStatus(runId, RunStatus.PARADO, decision.reason)
                            break@loop
                        }
                    }
                }
            }
            if (tracks.all { it.finished }) {
                setStatus(runId, RunStatus.CONCLUIDO, "Disputa encerrada para todos os itens armados.")
                break
            }
            delay(LOOP_MS)
        }
        safely { audit.record(AuditAction.ROBO_ENCERRADO, origin = AuditOrigin.ROBO, portal = Portal.COMPRAS_GOV, tenderNumber = "${t.number}/${t.year}", details = "Robô de lance: ${tracks.sumOf { it.bidsSent }} lance(s) enviado(s)") }
    }

    private suspend fun sendBid(
        ctrl: Control, runId: String, runner: PortalAutomationRunner, driver: PageDriver, t: PortalMyTender,
        tr: ItemTrack, value: Double, rule: com.licitaia.domain.model.BidRule, best: Double?, byUser: Boolean,
    ) {
        if (ctrl.stop) return
        // Trava final (mesma do motor assistido): nunca abaixo do piso nem acima do melhor lance.
        BidRuleEngine.validateBid(rule, value, best, captchaPending = false)?.let { reason ->
            log(runId, "Lance de ${Formatters.brl(value)} bloqueado: $reason"); return
        }
        val n = tr.item.itemNumber
        val text = TextNorm.formatInputMoney(value)
        val filled = runner.fill(PortalTargets.bidInput(n), text, ValueMatch::number)
            .takeIf { it.ok } ?: runner.fill(PortalTargets.bidInput(n).inScope(null), text, ValueMatch::number)
        if (!filled.ok) { log(runId, "Item $n: não consegui preencher o lance (${filled.describe()})."); ctrl.stop = true; setStatus(runId, RunStatus.PARADO, "Campo do lance não encontrado (item $n)."); return }
        val sent = runner.click(PortalTargets.bidSend(n)).takeIf { it.ok } ?: runner.click(PortalTargets.bidSend(n).inScope(null))
        if (!sent.ok) { log(runId, "Item $n: não consegui enviar (${sent.describe()})."); ctrl.stop = true; setStatus(runId, RunStatus.PARADO, "Botão de envio não encontrado (item $n)."); return }
        // Diálogo de confirmação do próprio portal (o lance já foi autorizado pelo usuário ao armar/tocar Enviar).
        if (runner.locate(PortalTargets.bidConfirm).first != null) runner.click(PortalTargets.bidConfirm)
        val now = System.currentTimeMillis()
        tr.lastOwnBidAt = now
        tr.bidsSent++
        _runs.update { m -> m[runId]?.let { r -> m + (runId to r.copy(bidsSent = r.bidsSent + 1, suggestion = null)) } ?: m }
        delay(2_500)
        val after = BidRoomParser.parse(driver.rows()).firstOrNull { it.itemNumber == n }
        val confirmed = after?.ourBid?.let { kotlin.math.abs(it - value) < 0.004 } == true
        log(runId, "Item $n: lance ${Formatters.brl(value)} enviado ${if (byUser) "(você tocou Enviar)" else "(automático)"}" + if (confirmed) " e confirmado na sala." else " — confirmação não lida na sala.")
        tr.sessionId?.let { sid ->
            safely {
                val r = live.recordOurBid(sid, value)
                if (r is BidResult.Rejected) log(runId, "Histórico: ${r.reason}")
                after?.position?.let { p -> live.setPosition(sid, p) }
            }
        }
        safely {
            audit.record(
                AuditAction.LANCE, AuditResult.SUCESSO, if (byUser) AuditOrigin.USUARIO else AuditOrigin.ROBO, portal = Portal.COMPRAS_GOV,
                tenderNumber = "${t.number}/${t.year}", item = "Item $n", newValue = Formatters.brl(value),
                details = "Lance enviado pelo robô (${if (byUser) "manual" else "automático"}); piso ${Formatters.brl(rule.floorPrice)}" + if (confirmed) "" else "; sem confirmação na leitura",
            )
        }
    }

    /** Sessão de Pregões ao Vivo do item (histórico de lances, Sala de Guerra e Concorrência). */
    private suspend fun findOrOpenSession(companyId: Long, t: PortalMyTender, item: ProposalItemPlan, plan: PortalRobotPlan): String? {
        val number = "${t.number}/${t.year}"
        val label = "Item ${item.itemNumber}"
        runCatching { live.restoreOrSeed(companyId) }
        live.sessions.value.firstOrNull { it.companyId == companyId && it.tenderNumber == number && it.itemLabel == label && it.status != LiveStatus.ENCERRADA }?.let { return it.id }
        return runCatching {
            live.openSession(
                LiveSessionSpec(
                    companyId = companyId, tenderId = t.matchedTenderId, portal = Portal.COMPRAS_GOV, tenderNumber = number,
                    agency = "UASG ${t.uasg}", itemLabel = label,
                    objectDescription = item.description.ifBlank { t.objectDescription }.take(300),
                    rule = RobotPlanRules.ruleFor(item, plan.bid), competitors = 0,
                ),
            )
        }.getOrNull()
    }

    /** Disputa do item encerrada: grava o resultado lido (alimenta Concorrência). Sem leitura clara, deixa para o usuário. */
    private suspend fun finishItemSession(tr: ItemTrack, ri: BidRoomItem?) {
        val sid = tr.sessionId ?: return
        val position = ri?.position ?: return
        val best = ri.bestBid ?: ri.ourBid ?: return
        safely { live.finishSession(sid, won = position == 1, finalValue = best) }
    }

    // ------------------------------------------------------------------ chat do pregoeiro

    private suspend fun readChat(companyId: Long, t: PortalMyTender, rows: List<String>, seen: MutableSet<String>, sessionId: String?) {
        val cnpj = auth.session.value?.activeCompany?.cnpj
        val parsed = ChatParser.parse(rows, cnpj)
        if (parsed.isEmpty()) return
        val number = "${t.number}/${t.year}"
        val existing = runCatching { withTimeoutOrNull(3_000) { messages.observeMessages(companyId).first() } }.getOrNull().orEmpty()
            .filter { it.tenderNumber == number }.map { TextNorm.norm(it.body).take(300) }.toHashSet()
        for (m in parsed) {
            val key = m.dedupKey(t.tenderKey)
            if (!seen.add(key)) continue
            if (TextNorm.norm(m.body).take(300) in existing) continue
            safely {
                messages.insert(
                    AuctioneerMessage(
                        companyId = companyId, sessionId = sessionId, portal = Portal.COMPRAS_GOV, tenderNumber = number,
                        sender = m.sender, body = m.body, receivedAt = m.at ?: System.currentTimeMillis(), urgent = m.urgent || m.directed,
                    ),
                )
            }
            if (m.urgent || m.directed) {
                safely {
                    notifier.notify(
                        NotificationCategory.MENSAGENS, if (m.directed) "Pregoeiro falou com você" else "Mensagem importante do pregoeiro",
                        "$number: ${m.body.take(180)}", critical = m.directed, route = Routes.MESSAGES, companyId = companyId,
                    )
                }
            }
        }
    }

    private companion object {
        const val LOOP_MS = 1_500L
        /** Tempo para a sala de disputa montar os itens antes de considerar a leitura ambígua. */
        const val ROOM_GRACE_MS = 90_000L
        /** Passo falho porque o WebView não está numa janela visível (app fechado / tela apagada). */
        const val APP_NOT_VISIBLE_KEY = "app.naoVisivel"
        /** Resultado de item que já estava com o valor do plano no portal. */
        const val ALREADY_SAVED = "já salvo"
    }
}
