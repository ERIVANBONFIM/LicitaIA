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
import com.licitaia.domain.portal.ProposalAuthorization
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

    /** Progresso na notificação persistente (ex.: "Item 12 de 77 · UASG …"); null = texto padrão. */
    fun updateProgress(text: String?) = Unit

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
 *   UASG + número DIGITADOS), abre o cadastro de proposta e CONFERE a compra (URL `compra=<código>` + cabeçalho
 *   "UASG"/"N° n/aaaa"; até 3 tentativas), confere a disponibilidade (prazo futuro, sem aviso de suspensa), aceita o
 *   termo e aplica as declarações da empresa SÓ com a autorização do usuário (sem ela, para e pede), abre grupos/lotes,
 *   carrega todos os itens e preenche/salva os itens SELECIONADOS ([SpaNavigator]) — por item ou pelo "Salvar" do
 *   grupo — conferindo o "Meu valor (unitário)". Item não encontrado não para tudo: entra na lista final. Nunca clica
 *   na lixeira.
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

    /**
     * "Soltar robô — cadastrar proposta" (a UI já mostrou a confirmação explícita). Com [authorization] (checkbox
     * "Autorizo o aceite do Termo…" marcado na confirmação) o robô aceita o termo e aplica as declarações da empresa;
     * sem ela, para e pede o usuário no termo (comportamento anterior). A autorização vai para a auditoria.
     */
    suspend fun startProposal(companyId: Long, tenderKey: String, authorization: ProposalAuthorization? = null): Result<String> = runCatching {
        requireOperator(companyId)
        activeRun(companyId, tenderKey, RobotKind.PROPOSTA)?.let { error("O robô de proposta já está rodando para esta licitação.") }
        val tender = myTender(companyId, tenderKey)
        val plan = repo.getPlan(companyId, tenderKey) ?: error("Configure os itens da proposta antes.")
        RobotPlanRules.proposalErrors(plan.items).firstOrNull()?.let { error(it) }
        val auth = authorization?.takeIf { it.acceptTerms }
        if (auth != null) {
            check(auth.declarations.complete) { "Responda as declarações da empresa antes de autorizar o termo." }
            safely {
                audit.record(
                    AuditAction.CONFIGURACAO, origin = AuditOrigin.USUARIO, portal = Portal.COMPRAS_GOV,
                    tenderNumber = "${tender.number}/${tender.year}", newValue = auth.declarations.summary(),
                    details = RobotPlanRules.authorizationAudit(tender, auth, plan.items),
                )
            }
        }
        launchRun(companyId, tender, RobotKind.PROPOSTA) { ctrl, id -> proposalRun(ctrl, id, companyId, tender, plan, auth) }
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

    /** Resultado de UM item no cadastro de proposta. */
    private sealed interface ItemOutcome {
        data object Saved : ItemOutcome
        data object AlreadySaved : ItemOutcome
        /** O item não está nesta compra (segue com os demais e lista no fim). */
        data class NotFound(val reason: String) : ItemOutcome
        /** Falha no preenchimento/salvamento do item (segue; 3 seguidas → para e pede o usuário). */
        data class Failed(val reason: String) : ItemOutcome
        /** Sessão/app/página errada: para e pede o usuário (nunca preenche fora da compra certa). */
        data class PageLost(val reason: String) : ItemOutcome
    }

    private suspend fun proposalRun(ctrl: Control, runId: String, companyId: Long, t: PortalMyTender, plan: PortalRobotPlan, auth: ProposalAuthorization?) {
        val lines = mutableListOf<String>()
        fun record(line: String) { lines += line; log(runId, line) }
        val nav = SpaNavigator(gate.driver(companyId), log = { log(runId, it) })
        val purchase = purchaseOf(t)
        val items = plan.items.filter { it.selected }.sortedBy { it.itemNumber }
        val total = items.size
        record("Robô de proposta solto por ${userName()}: $total de ${plan.items.size} item(ns) selecionados, total ${Formatters.brl(items.sumOf { it.totalPrice })}.")
        auth?.let { record("Termo/declarações autorizados por ${it.authorizedBy} em ${Formatters.dateTime(it.authorizedAt)}: ${it.declarations.summary()}.") }
        savePlan(companyId, t.tenderKey) { it.copy(proposalStatus = RobotProposalStatus.EXECUTANDO) }
        safely { audit.record(AuditAction.ROBO_ATIVADO, origin = AuditOrigin.USUARIO, portal = Portal.COMPRAS_GOV, tenderNumber = "${t.number}/${t.year}", details = "Robô de proposta: $total item(ns)") }

        val saved = LinkedHashSet<Int>()
        val failures = LinkedHashMap<Int, String>()
        var consecutiveFails = 0
        var position = 0
        var snapshotTaken = false

        val finish: suspend (RobotProposalStatus, String) -> Unit = { status, msg ->
            record(msg)
            savePlan(companyId, t.tenderKey) { it.copy(proposalStatus = status, proposalLog = lines.toList()) }
            safely { notifier.notify(NotificationCategory.SESSOES, "Robô de proposta: ${status.label}", "${t.label}: $msg", route = null, companyId = companyId) }
        }

        /** Primeira falha estrutural (item/grupo): grava a tela no "Mapear telas" para ajustar o robô. */
        suspend fun snapshotOnce(why: String) {
            if (snapshotTaken) return
            snapshotTaken = true
            if (runCatching { mapCurrentScreen(companyId) }.getOrDefault(false)) record("Tela do portal mapeada automaticamente ($why) para ajuste do robô.")
        }

        fun progress(text: String) {
            setStep(runId, "Item $position de $total · $text")
            runCatching { foreground.updateProgress("Item $position de $total · ${t.label}") }
        }

        /**
         * Um item no modo "Salvar por item". false = parar tudo (usuário parou). Dentro de um grupo ([group] = "Grupo 1:
         * 9/24"), item não encontrado PARA no grupo (o portal considera o grupo incompleto): pede o usuário e tenta de novo.
         */
        suspend fun runItem(item: ProposalItemPlan, group: (() -> String)? = null): Boolean {
            val n = item.itemNumber
            while (!ctrl.stop) {
                progress(listOfNotNull(group?.invoke(), "item $n: preenchendo").joinToString(" · "))
                when (val r = proposalItem(companyId, nav, purchase, item)) {
                    ItemOutcome.Saved -> {
                        saved += n; consecutiveFails = 0
                        record("Item $n: OK — ${Formatters.brl(item.unitPrice)} (unitário) salvo; o portal confirmou e o cartão mostra “Meu valor (unitário)” igual.")
                        return true
                    }
                    ItemOutcome.AlreadySaved -> {
                        saved += n; consecutiveFails = 0
                        record("Item $n: já estava com ${Formatters.brl(item.unitPrice)} no portal — nada a alterar.")
                        return true
                    }
                    is ItemOutcome.NotFound -> {
                        snapshotOnce("item $n não encontrado")
                        if (group == null) {
                            failures[n] = r.reason
                            record("Item $n: NÃO ENCONTRADO — ${r.reason}. Seguindo com os demais.")
                            return true
                        }
                        record("Item $n: NÃO ENCONTRADO no grupo (abri o grupo, rolei e paginei) — ${r.reason}.")
                        val ok = awaitUser(
                            ctrl, runId,
                            "${group()}: o item $n não apareceu mesmo abrindo o grupo, rolando e paginando. O portal considera o grupo " +
                                "incompleto, então o robô NÃO segue para outro grupo. Abra o item no portal e toque em Tentar de novo (ou Continuar manualmente).",
                            showPortal = true,
                        )
                        if (!ok) { failures[n] = r.reason; return false }
                    }
                    is ItemOutcome.Failed -> {
                        record("Item $n: FALHOU — ${r.reason}.")
                        if (++consecutiveFails < 3) { failures[n] = r.reason; return true }
                        val ok = awaitUser(
                            ctrl, runId,
                            "3 itens seguidos falharam (último: item $n — ${r.reason}). Confira no portal e toque em Tentar de novo (ou Continuar manualmente).",
                            showPortal = true,
                        )
                        if (!ok) { failures[n] = r.reason; return false }
                        consecutiveFails = 0
                    }
                    is ItemOutcome.PageLost -> {
                        record("Item $n: PARADO — ${r.reason}.")
                        if (!awaitUser(ctrl, runId, "Item $n: ${r.reason}. Confira no portal e toque em Tentar de novo (ou Continuar manualmente).", showPortal = true)) return false
                    }
                }
            }
            return false
        }

        /** Grupo com UM "Salvar" para todos: digita e confere cada item, salva o grupo uma vez e relê cada item. */
        suspend fun runGroupBatch(g: GroupStructure, button: String, groupItems: List<ProposalItemPlan>): Boolean {
            val values = mutableListOf<Pair<Int, Double>>()
            for (item in groupItems) {
                if (ctrl.stop) return false
                val n = item.itemNumber
                position++
                progress("${g.label.take(30)} · item $n: digitando")
                var outcome: ItemOutcome? = null
                while (!ctrl.stop) {
                    outcome = fillOnly(companyId, nav, purchase, item)
                    if (outcome !is ItemOutcome.PageLost) break
                    record("Item $n: PARADO — ${outcome.reason}.")
                    if (!awaitUser(ctrl, runId, "Item $n: ${outcome.reason}. Confira no portal e toque em Tentar de novo.", showPortal = true)) return false
                }
                when (outcome) {
                    null -> { values += n to item.unitPrice }
                    ItemOutcome.AlreadySaved -> { saved += n; record("Item $n: já estava com ${Formatters.brl(item.unitPrice)} no portal.") }
                    is ItemOutcome.NotFound -> { failures[n] = outcome.reason; record("Item $n: NÃO ENCONTRADO — ${outcome.reason}."); snapshotOnce("item $n não encontrado no grupo") }
                    is ItemOutcome.Failed -> { failures[n] = outcome.reason; record("Item $n: FALHOU — ${outcome.reason}.") }
                    else -> Unit
                }
            }
            if (values.isEmpty()) return true
            if (ctrl.stop) return false
            setStep(runId, "Item $position de $total · salvando ${g.label.take(30)} (${values.size} item(ns))")
            record("${g.label}: ${values.size} valor(es) digitado(s) e conferido(s); tocando uma vez em “$button” do grupo.")
            val res = nav.saveGroup(g, values)
            res.forEach { (n, err) ->
                if (err == null) {
                    saved += n
                    record("Item $n: OK — ${Formatters.brl(values.first { it.first == n }.second)} salvo pelo grupo e relido em “Meu valor (unitário)”.")
                } else {
                    failures[n] = err
                    record("Item $n: FALHOU no salvar do grupo — $err.")
                }
            }
            if (res.values.any { it != null }) snapshotOnce("salvar do ${g.label}")
            return true
        }

        /**
         * Um GRUPO/LOTE inteiro: todos os itens selecionados dele (relocalizando o grupo/itens depois de cada Salvar, pois
         * o portal redesenha e fecha o grupo) e, no fim, o cartão do grupo não pode continuar "Proposta incompleta" —
         * só então o robô passa para o próximo grupo. false = parar tudo.
         */
        suspend fun runGroup(g: GroupStructure, inGroup: List<ProposalItemPlan>): Boolean {
            val card0 = GroupCard.parse(g.text)
            val key = card0?.key ?: g.label.take(30)
            fun groupProgress(): String {
                val done = g.items.count { it in saved }
                return card0?.progress(done) ?: "${g.label.take(30)}: $done/${g.items.size}"
            }
            record("$key: ${inGroup.size} item(ns) selecionado(s) de ${card0?.itemCount ?: g.items.size}" + if (card0?.incomplete == true) " · portal mostra “Proposta incompleta”." else ".")
            when (val s = GroupPlanner.decide(g)) {
                is GroupStrategy.GroupSave -> if (!runGroupBatch(g, s.button, inGroup)) return false
                is GroupStrategy.Stop -> {
                    record("${g.label}: ${s.reason}. O robô não preenche este grupo.")
                    snapshotOnce("estrutura do ${g.label}")
                    inGroup.forEach { failures[it.itemNumber] = "grupo não preenchido pelo robô (${s.reason})" }
                    position += inGroup.size
                    // Grupo incompleto: não segue para outro grupo sem o usuário.
                    return awaitUser(
                        ctrl, runId,
                        "${g.label}: ${s.reason}. Preencha este grupo no portal e toque em Tentar de novo para seguir (ou Continuar manualmente).",
                        showPortal = true,
                    )
                }
                GroupStrategy.PerItem -> {
                    // Página por página do paginador DO GRUPO: preenche todos os selecionados visíveis, vai para a próxima.
                    val wanted = inGroup.associateBy { it.itemNumber }
                    val seen = HashSet<Int>()
                    var page = 1
                    var pages = 1
                    var finished = false
                    var guard = 0
                    while (!ctrl.stop && !finished && guard++ < 60) {
                        val err = nav.ensureGroupPage(key, page)
                        if (err != null) {
                            record("$key: $err.")
                            if (!awaitUser(ctrl, runId, "$key: $err. Abra a página $page do grupo no portal e toque em Tentar de novo.", showPortal = true)) return false
                            continue
                        }
                        val visible = nav.groupItems(key)
                        seen += visible
                        if (page == 1) pages = GroupTraversal.expectedPages(card0?.itemCount, visible.size, nav.groupPager(key))
                        val todo = visible.mapNotNull { wanted[it] }.filter { it.itemNumber !in saved && it.itemNumber !in failures }
                        if (visible.isNotEmpty()) record("$key · página $page/$pages: itens ${visible.first()}–${visible.last()} (${todo.size} a preencher).")
                        val currentPage = page
                        val label = { GroupTraversal.progress(key, currentPage, pages, g.items.count { it in saved }, card0?.itemCount) }
                        for (item in todo) {
                            if (ctrl.stop) return false
                            // Depois de salvar o portal pode fechar o grupo / voltar para a página 1.
                            nav.ensureGroupPage(key, page)
                            position++
                            if (!runItem(item, label)) return false
                        }
                        nav.ensureGroupPage(key, page)
                        val pg = nav.groupPager(key)
                        if (pg?.hasNext == true) {
                            page++
                            pages = maxOf(pages, page, pg.lastKnownPage)
                        } else {
                            finished = true
                        }
                    }
                    // Selecionados que não apareceram em nenhuma página do grupo: o grupo fica incompleto → para nele.
                    for (item in inGroup.filter { it.itemNumber !in seen && it.itemNumber !in saved && it.itemNumber !in failures }) {
                        position++
                        if (!runItem(item, ::groupProgress)) return false
                    }
                }
            }
            // "Proposta incompleta" ainda no cartão do grupo? Não passa para o próximo grupo sem o usuário.
            var checks = 0
            while (!ctrl.stop && checks < 2) {
                checks++
                nav.prepareGroups()
                val card = nav.groupCard(key) ?: run { record("$key: não consegui reler o cartão do grupo."); return true }
                if (!card.incomplete && !card.notRegistered) {
                    record("$key completo: ${groupProgress()}${card.myTotal?.let { " · Meu valor (total) R$ ${it.toPlainString().replace('.', ',')}" }.orEmpty()}.")
                    nav.collapseGroup(key)
                    return true
                }
                val pending = inGroup.filter { it.itemNumber !in saved }.map { it.itemNumber }
                val why = if (pending.isNotEmpty()) "itens selecionados ainda sem valor: ${pending.joinToString()}"
                else "o portal pede TODOS os ${card.itemCount ?: "?"} itens do grupo e o plano tem ${inGroup.size} selecionado(s)"
                record("$key continua “Proposta incompleta” (${groupProgress()}): $why.")
                if (checks >= 2) { record("$key segue incompleto; seguindo por decisão sua."); return true }
                if (!awaitUser(
                        ctrl, runId,
                        "$key continua “Proposta incompleta” ($why). O robô não passa para outro grupo: complete no portal e toque em Tentar de novo (ou Continuar manualmente).",
                        showPortal = true,
                    )
                ) return false
            }
            return !ctrl.stop
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
            if (!step(ctrl, runId, "Localizar e abrir a compra ${t.label}") { openPurchase(companyId, nav, t) }) {
                finish(RobotProposalStatus.AGUARDANDO_USUARIO, "Parado ao abrir a compra."); return
            }
            record("Cadastro de proposta da compra certa aberto e conferido (compra=${CompraCode.of(t.uasg, t.modality, t.number, t.year) ?: "${t.uasg}…${t.number}/${t.year}"}; cabeçalho com UASG ${purchase.uasgShown} e N° ${purchase.numberYear}).")
            val av = nav.availability()
            if (av != null && !av.ok) {
                finish(RobotProposalStatus.FALHOU, "Compra indisponível: ${av.describe()}. Nada foi preenchido.")
                setStatus(runId, RunStatus.FALHOU, lines.lastOrNull())
                return
            }
            record("Disponibilidade: ${av?.describe() ?: "não consegui ler o prazo na página"}.")
            if (!handleDeclarations(ctrl, runId, companyId, nav, t, auth, ::record)) {
                finish(RobotProposalStatus.AGUARDANDO_USUARIO, "Parado aguardando o termo/declarações."); return
            }
            setStep(runId, "Abrindo grupos/lotes e carregando os itens")
            val groups = nav.prepareGroups()
            if (groups.isNotEmpty()) {
                record("Compra com ${groups.size} grupo(s)/lote(s): " + groups.joinToString { "${it.label.take(40)} (${it.items.size} item(ns))" } + ".")
            }
            val handled = HashSet<Int>()
            for (item in items) {
                if (ctrl.stop) break
                if (item.itemNumber in handled) continue
                val g = groups.firstOrNull { item.itemNumber in it.items }
                if (g != null) {
                    val inGroup = items.filter { it.itemNumber in g.items && it.itemNumber !in handled }
                    handled += inGroup.map { it.itemNumber }
                    if (!runGroup(g, inGroup)) break
                    continue
                }
                handled += item.itemNumber
                position++
                if (!runItem(item)) break
            }
        } finally {
            gate.releaseTab(owner)
            runCatching { foreground.updateProgress(null) }
        }
        val savedValue = items.filter { it.itemNumber in saved }.sumOf { it.totalPrice }
        val failText = failures.entries.take(12).joinToString("; ") { (n, why) -> "item $n: ${why.take(90)}" } +
            if (failures.size > 12) "; +${failures.size - 12}" else ""
        when {
            saved.size == total -> {
                finish(RobotProposalStatus.CADASTRADA, "Proposta cadastrada: ${saved.size} de $total itens salvos · total ${Formatters.brl(savedValue)}. Confira no portal antes do prazo.")
                safely { repo.upsertMyTenders(companyId, listOf(t.copy(hasProposal = true, updatedAt = System.currentTimeMillis()))) }
            }
            saved.isNotEmpty() -> finish(
                RobotProposalStatus.PARCIAL,
                "Proposta em parte: ${saved.size} de $total itens salvos · total ${Formatters.brl(savedValue)}." + if (failText.isNotBlank()) " Faltam: $failText." else "",
            )
            else -> finish(RobotProposalStatus.FALHOU, "Nenhum item foi salvo." + if (failText.isNotBlank()) " $failText." else "")
        }
        safely {
            audit.record(
                AuditAction.ENVIO, if (saved.size == total) AuditResult.SUCESSO else AuditResult.FALHA, AuditOrigin.ROBO, portal = Portal.COMPRAS_GOV,
                tenderNumber = "${t.number}/${t.year}", details = "Robô de proposta: ${saved.size}/$total item(ns) salvos" + if (failures.isNotEmpty()) "; falhas: ${failures.keys.joinToString()}" else "",
            )
        }
        setStatus(runId, if (saved.size == total) RunStatus.CONCLUIDO else if (ctrl.stop) RunStatus.PARADO else RunStatus.FALHOU, lines.lastOrNull())
    }

    /**
     * Termo/declarações. COM autorização do usuário: aceita o Termo (Marcar todas, confere uma a uma, Confirmar) e
     * aplica as respostas da empresa nos rádios; qualquer pendência → para sem confirmar e mostra o portal. SEM
     * autorização: só lê; faltando algo, para e pede o usuário ([checkDeclarations]).
     */
    private suspend fun handleDeclarations(
        ctrl: Control, runId: String, companyId: Long, nav: SpaNavigator, t: PortalMyTender, auth: ProposalAuthorization?, record: (String) -> Unit,
    ): Boolean {
        if (auth == null) return checkDeclarations(ctrl, runId, nav, record)
        setStep(runId, "Termo e declarações (autorizado por ${auth.authorizedBy})")
        while (!ctrl.stop) {
            if (!gate.ensureLive(companyId)) {
                if (!awaitUser(ctrl, runId, PortalSessionGate.OPEN_APP_MESSAGE + " Depois toque em Tentar de novo.", attention = RobotAttention.OPEN_APP)) return false
                continue
            }
            val before = nav.declarations()
            if (before != null && !before.section) {
                record("A página não mostra o bloco Termo/declarações; seguindo.")
                return true
            }
            val already = before != null && DeclarationsCheck.missing(before).isEmpty() && DeclarationRadios.plan(before, auth.declarations).first.isEmpty()
            if (already) {
                record("Termo já aceito e declarações já iguais às da empresa (${auth.declarations.summary()}).")
                return true
            }
            val termErr = nav.acceptTerms()
            val declErr = if (termErr == null) nav.applyDeclarations(auth.declarations) else null
            val after = nav.declarations()
            val missing = after?.let(DeclarationsCheck::missing) ?: listOf("não consegui ler o bloco Termo/declarações")
            if (termErr == null && declErr == null && missing.isEmpty()) {
                record("Termo de Aceitação aceito (todas as declarações do modal conferidas antes de Confirmar) e declarações aplicadas: ${auth.declarations.summary()}.")
                safely {
                    audit.record(
                        AuditAction.CONFIGURACAO, origin = AuditOrigin.ROBO, portal = Portal.COMPRAS_GOV, tenderNumber = "${t.number}/${t.year}",
                        newValue = auth.declarations.summary(),
                        details = "Robô aceitou o Termo de Aceitação e aplicou as declarações da empresa (autorizado por ${auth.authorizedBy} em ${Formatters.dateTime(auth.authorizedAt)})",
                    )
                }
                return true
            }
            val why = termErr ?: declErr ?: missing.joinToString("; ")
            record("Termo/declarações: $why. O robô NÃO confirmou nada pendente.")
            if (!awaitUser(
                    ctrl, runId, "Termo/declarações: $why. Confira no portal e toque em Continuar (o robô confere de novo).",
                    attention = RobotAttention.DECLARATIONS, showPortal = true,
                )
            ) return false
        }
        return false
    }

    /**
     * Termo/declarações (LEGAIS) sem autorização: o robô só LÊ. Faltando algo, para, abre a tela do portal com a faixa
     * "Marque o termo e as declarações e toque em Continuar" e confere de novo depois do toque. false = parado.
     */
    private suspend fun checkDeclarations(ctrl: Control, runId: String, nav: SpaNavigator, record: (String) -> Unit): Boolean {
        setStep(runId, "Conferir termo e declarações (sem autorização: o robô não marca)")
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
            val msg = "Marque o termo e as declarações e toque em Continuar. Falta: ${missing.joinToString("; ")}. " +
                "(Para o robô fazer isso, autorize o termo na confirmação “Soltar o robô”.)"
            if (!awaitUser(ctrl, runId, msg, attention = RobotAttention.DECLARATIONS, showPortal = true)) return false
            confirmed = true
        }
        return false
    }

    /** Página certa + cartão do item [item] (número exato). null = pronto para preencher; senão o resultado final. */
    private suspend fun reachItem(companyId: Long, nav: SpaNavigator, p: SpaNavigator.Purchase, item: ProposalItemPlan): ItemOutcome? {
        if (!gate.ensureLive(companyId)) return ItemOutcome.PageLost(PortalSessionGate.OPEN_APP_MESSAGE)
        // Ainda na página da compra certa? (o portal pode ter navegado) — nunca preenche fora dela.
        nav.openPurchase(p)?.let { return ItemOutcome.PageLost("a página da compra certa não está aberta ($it)") }
        val n = item.itemNumber
        val card = nav.locateItem(n) ?: run {
            val seen = nav.visibleItems()
            val hint = if (seen.isEmpty()) "nenhum item visível" else "itens visíveis: ${seen.take(8).joinToString()}${if (seen.size > 8) "…" else ""}"
            return ItemOutcome.NotFound("o item $n não aparece no cadastro de proposta desta compra ($hint)")
        }
        if (card.myUnitValue != null && ProposalMoney.matches(item.unitPrice, card.myUnitValue.toPlainString().replace('.', ','))) return ItemOutcome.AlreadySaved
        return null
    }

    /** Abre o formulário do item e digita/confere os campos (sem salvar). null = pronto para salvar. */
    private suspend fun fillOnly(companyId: Long, nav: SpaNavigator, p: SpaNavigator.Purchase, item: ProposalItemPlan): ItemOutcome? {
        reachItem(companyId, nav, p, item)?.let { return it }
        val n = item.itemNumber
        nav.openItemForm(n)?.let { return ItemOutcome.Failed(it) }
        nav.fillOptional(n, "quantidade ofertada", TextNorm.formatInputNumber(item.quantity), numeric = true)?.let { return ItemOutcome.Failed(it) }
        nav.fillItemValue(n, item.unitPrice)?.let { return ItemOutcome.Failed(it) }
        nav.fillOptional(n, "marca", item.brand, numeric = false)?.let { return ItemOutcome.Failed(it) }
        nav.fillOptional(n, "fabricante", item.manufacturer, numeric = false)?.let { return ItemOutcome.Failed(it) }
        nav.fillOptional(n, "modelo", item.modelVersion, numeric = false)?.let { return ItemOutcome.Failed(it) }
        nav.fillOptional(n, "descricao detalhada", item.detailedDescription, numeric = false)?.let { return ItemOutcome.Failed(it) }
        return null
    }

    /** Preenche e salva UM item (Salvar do próprio bloco), relendo "Meu valor (unitário)". */
    private suspend fun proposalItem(companyId: Long, nav: SpaNavigator, p: SpaNavigator.Purchase, item: ProposalItemPlan): ItemOutcome {
        fillOnly(companyId, nav, p, item)?.let { return it }
        // Valor ainda certo logo antes de salvar (nada mexeu no campo).
        return nav.saveItem(item.itemNumber, item.unitPrice)?.let { ItemOutcome.Failed(it) } ?: ItemOutcome.Saved
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
