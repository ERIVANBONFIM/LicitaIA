package com.licitaia.feature.bidding.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.licitaia.core.ui.components.AlertBanner
import com.licitaia.core.ui.components.ConfirmDialog
import com.licitaia.core.ui.components.DangerButton
import com.licitaia.core.ui.components.LicitaCard
import com.licitaia.core.ui.components.LicitaScaffold
import com.licitaia.core.ui.components.PrimaryButton
import com.licitaia.core.ui.components.SecondaryButton
import com.licitaia.core.ui.components.SectionHeader
import com.licitaia.core.ui.components.StatusBadge
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.nav.LocalAppNavigator
import com.licitaia.core.ui.nav.Routes
import com.licitaia.core.ui.theme.LicitaColors
import com.licitaia.domain.model.BidStrategy
import com.licitaia.domain.model.Portal
import com.licitaia.domain.portal.BidRobotConfig
import com.licitaia.domain.portal.BidRobotMode
import com.licitaia.domain.portal.PortalMyTender
import com.licitaia.domain.portal.PortalRobotPlan
import com.licitaia.domain.portal.PortalRobotRepository
import com.licitaia.domain.portal.ProposalItemPlan
import com.licitaia.domain.portal.ProposalRobotMapping
import com.licitaia.domain.portal.RobotProposalStatus
import com.licitaia.domain.repository.AuthRepository
import com.licitaia.domain.repository.ProposalRepository
import com.licitaia.domain.security.Permission
import com.licitaia.domain.security.Rbac
import com.licitaia.domain.util.Formatters
import com.licitaia.feature.bidding.robot.RobotScheduler
import com.licitaia.feature.live.automation.PortalMarker
import com.licitaia.feature.live.automation.PortalRobotEngine
import com.licitaia.feature.live.automation.ProposalReadingRules
import com.licitaia.feature.live.automation.PurchaseText
import com.licitaia.feature.live.automation.RobotKind
import com.licitaia.feature.live.automation.RobotPlanRules
import com.licitaia.feature.live.automation.RobotRun
import com.licitaia.feature.live.automation.TextNorm
import com.licitaia.feature.live.ui.proposalTone
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

data class RobotPlanUiState(
    val loading: Boolean = true,
    val companyId: Long? = null,
    val canOperate: Boolean = false,
    val tender: PortalMyTender? = null,
    val plan: PortalRobotPlan? = null,
    val runs: List<RobotRun> = emptyList(),
    /** Declarações padrão do Compras.gov da empresa ativa. */
    val declarations: com.licitaia.domain.model.PortalDeclarations = com.licitaia.domain.model.PortalDeclarations(),
    val userName: String = "",
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RobotPlanViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val auth: AuthRepository,
    private val repo: PortalRobotRepository,
    private val proposals: ProposalRepository,
    private val engine: PortalRobotEngine,
    private val scheduler: RobotScheduler,
    private val strategies: com.licitaia.domain.bidding.BidStrategyConfigRepository,
    private val companies: com.licitaia.domain.repository.CompanyRepository,
) : ViewModel() {
    /** Padrões da tela Estratégias para um robô ainda não configurado. */
    suspend fun strategyDefaults(): com.licitaia.domain.bidding.BidStrategyConfig? =
        state.value.companyId?.let { id -> runCatching { strategies.get(id) }.getOrNull() }

    val key: String = savedStateHandle.get<String>("key").orEmpty()
    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    val state: StateFlow<RobotPlanUiState> = auth.session.flatMapLatest { s ->
        val id = s?.activeCompany?.id ?: return@flatMapLatest flowOf(RobotPlanUiState(loading = false))
        combine(repo.observeMyTenders(id), repo.observePlan(id, key), engine.runs) { tenders, plan, runs ->
            RobotPlanUiState(
                loading = false, companyId = id,
                canOperate = !s.user.demo && Rbac.can(s.user.role, Permission.OPERAR_SESSOES),
                tender = tenders.firstOrNull { it.tenderKey == key }, plan = plan,
                runs = runs.values.filter { it.companyId == id && it.tenderKey == key }.sortedByDescending { it.startedAt },
                declarations = s.activeCompany.portalDeclarations, userName = s.user.name,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RobotPlanUiState())

    private fun basePlan(): PortalRobotPlan? {
        val st = state.value
        val id = st.companyId ?: return null
        return st.plan ?: PortalRobotPlan(companyId = id, tenderKey = key, sessionAt = st.tender?.openingAt)
    }

    /**
     * Itens da proposta do app (licitação vinculada) para pré-preencher (editáveis): a liberada mais recente, senão a
     * aprovada, senão a última versão — com o nº do item do edital, quantidade, valor e marca/fabricante/modelo.
     * Pisos já definidos no plano para o mesmo item são mantidos.
     */
    suspend fun appProposalItems(current: List<ProposalItemPlan> = emptyList()): List<ProposalItemPlan> {
        val tenderId = state.value.tender?.matchedTenderId ?: return emptyList()
        val list = runCatching { withTimeoutOrNull(4_000) { proposals.observeProposals(tenderId).first() } }.getOrNull().orEmpty()
        val p = ProposalRobotMapping.preferredProposal(list) ?: return emptyList()
        return ProposalRobotMapping.mergeFloors(ProposalRobotMapping.toPlanItems(p), current)
    }

    /** Há licitação do app vinculada (proposta do app pode preencher o plano). */
    val hasAppTender: Boolean get() = state.value.tender?.matchedTenderId != null

    fun saveItems(items: List<ProposalItemPlan>, sessionAt: Long?, onDone: () -> Unit = {}) {
        val base = basePlan() ?: return
        viewModelScope.launch {
            val ready = RobotPlanRules.proposalErrors(items).isEmpty()
            runCatching {
                repo.savePlan(
                    base.copy(
                        items = items, sessionAt = sessionAt ?: base.sessionAt,
                        proposalStatus = if (base.proposalStatus == RobotProposalStatus.NAO_CONFIGURADA || base.proposalStatus == RobotProposalStatus.PRONTA) {
                            if (ready) RobotProposalStatus.PRONTA else RobotProposalStatus.NAO_CONFIGURADA
                        } else base.proposalStatus,
                    ),
                )
            }.onSuccess { _events.send("Plano salvo."); onDone() }.onFailure { _events.send(it.message ?: "Falha ao salvar.") }
        }
    }

    /**
     * Confirmação "Soltar o robô": salva a seleção de itens no plano, grava as declarações escolhidas no cadastro da
     * empresa (se mudaram) e solta o robô com a autorização do Termo/declarações (registrada na auditoria pelo motor).
     */
    /**
     * "Ler situação no portal": salva os itens do plano (a leitura fica guardada neles) e solta a LEITURA (nada é
     * preenchido). O resultado chega pelo plano ([PortalRobotPlan.portalReading]).
     */
    fun readPortal(items: List<ProposalItemPlan>, sessionAt: Long?) {
        val id = state.value.companyId ?: return
        val base = basePlan() ?: return
        viewModelScope.launch {
            runCatching { repo.savePlan(base.copy(items = items, sessionAt = sessionAt ?: base.sessionAt)) }
                .onFailure { _events.send(it.message ?: "Falha ao salvar o plano."); return@launch }
            engine.startPortalReading(id, key).onSuccess { _events.send("Lendo a situação no portal (nada será preenchido)…") }
                .onFailure { _events.send(it.message ?: "Não foi possível ler o portal.") }
        }
    }

    fun startProposal(
        items: List<ProposalItemPlan>, sessionAt: Long?, declarations: com.licitaia.domain.model.PortalDeclarations, saveDeclarations: Boolean,
        updateDifferent: Boolean = false,
    ) {
        val id = state.value.companyId ?: return
        viewModelScope.launch {
            if (saveDeclarations) {
                runCatching { companies.updatePortalDeclarations(id, declarations) }
                    .onSuccess { _events.send("Declarações salvas no cadastro da empresa.") }
                    .onFailure { _events.send(it.message ?: "Não foi possível salvar as declarações."); return@launch }
            }
            val base = basePlan() ?: return@launch
            runCatching { repo.savePlan(base.copy(items = items, sessionAt = sessionAt ?: base.sessionAt, proposalStatus = if (base.proposalStatus == RobotProposalStatus.NAO_CONFIGURADA) RobotProposalStatus.PRONTA else base.proposalStatus)) }
                .onFailure { _events.send(it.message ?: "Falha ao salvar o plano."); return@launch }
            val auth = com.licitaia.domain.portal.ProposalAuthorization(
                acceptTerms = true, declarations = declarations, authorizedBy = state.value.userName.ifBlank { "Operador" }, authorizedAt = System.currentTimeMillis(),
            )
            engine.startProposal(id, key, auth, updateDifferent).onSuccess { _events.send("Robô de proposta solto. Acompanhe aqui ou no portal.") }
                .onFailure { _events.send(it.message ?: "Não foi possível iniciar.") }
        }
    }

    fun saveBid(config: BidRobotConfig, sessionAt: Long?, arm: Boolean) {
        val base = basePlan() ?: return
        viewModelScope.launch {
            val plan = base.copy(bid = config, sessionAt = sessionAt ?: base.sessionAt, bidArmedAt = if (arm) System.currentTimeMillis() else null)
            runCatching { repo.savePlan(plan) }.onFailure { _events.send(it.message ?: "Falha ao salvar."); return@launch }
            // Armado: a licitação entra em Pregões ao Vivo / Sala de Guerra ("Aguardando abertura") com o piso de cada item.
            if (arm) runCatching { engine.ensureLiveSessions(plan.companyId, key) }
            val at = plan.sessionAt
            if (arm && at != null && at > System.currentTimeMillis()) {
                scheduler.schedule(plan.companyId, key, at)
                _events.send("Robô de lance armado. Agenda: preparar ${Formatters.dateTime(RobotPlanRules.prepareAt(at))}.")
            } else if (arm) {
                _events.send("Robô de lance armado (sem horário futuro: use “Entrar na disputa agora”).")
            } else {
                scheduler.cancel(plan.companyId, key)
                _events.send("Robô de lance desarmado.")
            }
        }
    }

    fun startBidNow() {
        val id = state.value.companyId ?: return
        viewModelScope.launch {
            engine.startBid(id, key).onSuccess { _events.send("Robô entrando na sala de disputa.") }.onFailure { _events.send(it.message ?: "Não foi possível iniciar.") }
        }
    }

    fun stop(runId: String) = engine.stop(runId)
    fun continueRun(runId: String) = engine.continueRun(runId)
    fun sendSuggestion(runId: String) = engine.sendSuggestion(runId)
}

/** Linha editável de item (texto bruto dos campos). */
private class ItemForm(
    number: String = "", qty: String = "", price: String = "", floor: String = "", brand: String = "", maker: String = "",
    model: String = "", detail: String = "", val description: String = "", selected: Boolean = true,
) {
    var number by mutableStateOf(number); var qty by mutableStateOf(qty); var price by mutableStateOf(price); var floor by mutableStateOf(floor)
    var brand by mutableStateOf(brand); var maker by mutableStateOf(maker); var model by mutableStateOf(model); var detail by mutableStateOf(detail)
    /** Participar deste item (o robô de proposta só cadastra os selecionados). */
    var selected by mutableStateOf(selected)

    fun toPlan(): ProposalItemPlan? {
        val n = number.trim().toIntOrNull() ?: return null
        return ProposalItemPlan(
            itemNumber = n, description = description, quantity = TextNorm.parseMoney(qty) ?: 0.0, unitPrice = TextNorm.parseMoney(price) ?: 0.0,
            brand = brand.trim(), manufacturer = maker.trim(), modelVersion = model.trim(), detailedDescription = detail.trim(),
            floorUnitPrice = floor.takeIf { it.isNotBlank() }?.let { TextNorm.parseMoney(it) },
        ).let { it.copy(selected = selected && it.hasPrice) }
    }

    companion object {
        fun of(i: ProposalItemPlan) = ItemForm(
            i.itemNumber.toString(), TextNorm.formatInputNumber(i.quantity), TextNorm.formatInputMoney(i.unitPrice),
            i.floorUnitPrice?.let(TextNorm::formatInputMoney).orEmpty(), i.brand, i.manufacturer, i.modelVersion, i.detailedDescription, i.description, i.selected,
        )
    }
}

@Composable
fun RobotPlanScreen(vm: RobotPlanViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    LaunchedEffect(Unit) { vm.events.collect { navigator.showMessage(it) } }
    val tender = state.tender

    val scope = rememberCoroutineScope()
    val items = remember { mutableStateListOf<ItemForm>() }
    var loadedFrom by remember { mutableStateOf<Long?>(null) }
    var sessionText by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(BidRobotMode.MANUAL) }
    var strategy by remember { mutableStateOf(BidStrategy.CONSERVADORA) }
    var reduction by remember { mutableStateOf("1,00") }
    var decrement by remember { mutableStateOf("0,01") }
    var ownInterval by remember { mutableStateOf("20") }
    var afterBest by remember { mutableStateOf("3") }
    var maxBids by remember { mutableStateOf("30") }
    var onTotal by remember { mutableStateOf(false) }
    var confirmProposal by remember { mutableStateOf<List<ProposalItemPlan>?>(null) }
    var confirmBid by remember { mutableStateOf<String?>(null) }
    /** "Ler situação no portal" pedido: quando a leitura nova chegar, pré-seleciona só os não cadastrados. */
    var awaitingReadSince by remember { mutableStateOf<Long?>(null) }
    val reading = state.plan?.portalReading
    LaunchedEffect(reading?.readAt) {
        val since = awaitingReadSince ?: return@LaunchedEffect
        val r = reading ?: return@LaunchedEffect
        if (r.readAt < since) return@LaunchedEffect
        awaitingReadSince = null
        val chosen = ProposalReadingRules.preselect(items.mapNotNull { it.toPlan() }, r, state.declarations.meEpp).associateBy { it.itemNumber }
        items.forEach { f -> f.number.trim().toIntOrNull()?.let { n -> chosen[n]?.let { f.selected = it.selected } } }
        navigator.showMessage("Pré-selecionados só os itens “Proposta não cadastrada”. ${ProposalReadingRules.summary(items.mapNotNull { it.toPlan() }, r, state.declarations.meEpp)}")
    }

    // Carrega o plano salvo UMA vez nos campos (depois o usuário edita).
    LaunchedEffect(state.loading, state.plan?.updatedAt) {
        if (state.loading || loadedFrom != null) return@LaunchedEffect
        val plan = state.plan
        loadedFrom = plan?.updatedAt ?: 0L
        items.clear()
        plan?.items?.forEach { items += ItemForm.of(it) }
        if (items.isEmpty()) vm.appProposalItems().forEach { items += ItemForm.of(it) }
        if (items.isEmpty()) items += ItemForm(number = "1")
        (plan?.sessionAt ?: tender?.openingAt)?.let { sessionText = Formatters.dateTime(it) }
        if (plan == null) vm.strategyDefaults()?.let { d ->
            strategy = d.strategy; ownInterval = d.minIntervalSeconds.coerceAtLeast(20).toString()
            if (d.decrementMode == com.licitaia.domain.bidding.DecrementMode.VALOR) { decrement = TextNorm.formatInputMoney(d.decrementValue); reduction = TextNorm.formatInputMoney(d.decrementValue) }
        }
        plan?.bid?.let { b ->
            mode = if (b.mode == BidRobotMode.DESLIGADO) BidRobotMode.MANUAL else b.mode; strategy = b.strategy
            reduction = TextNorm.formatInputMoney(b.reductionValue); decrement = TextNorm.formatInputMoney(b.minDecrement)
            ownInterval = b.ownIntervalSeconds.toString(); afterBest = b.afterBestSeconds.toString(); maxBids = b.maxBids.toString(); onTotal = b.bidOnTotal
        }
    }

    fun planItems() = items.mapNotNull { it.toPlan() }
    fun sessionAt(): Long? = PurchaseText.dateTime(sessionText)
    fun bidConfig() = BidRobotConfig(
        mode = mode, strategy = strategy,
        minDecrement = TextNorm.parseMoney(decrement) ?: 0.01, reductionValue = TextNorm.parseMoney(reduction) ?: 1.0,
        ownIntervalSeconds = (ownInterval.toIntOrNull() ?: 20).coerceAtLeast(BidRobotConfig.MIN_OWN_INTERVAL_SECONDS),
        afterBestSeconds = (afterBest.toIntOrNull() ?: 3).coerceAtLeast(BidRobotConfig.MIN_AFTER_BEST_SECONDS),
        maxBids = (maxBids.toIntOrNull() ?: 30).coerceAtLeast(1), bidOnTotal = onTotal,
    )

    confirmProposal?.let { list ->
        if (tender != null) {
            ProposalConfirmDialog(
                tender = tender, planItems = list, companyDeclarations = state.declarations,
                reading = reading,
                readingActive = state.runs.any { it.active && it.kind == RobotKind.LEITURA },
                onReadPortal = { chosen ->
                    val byNumber = chosen.associateBy { it.itemNumber }
                    items.forEach { f -> f.number.trim().toIntOrNull()?.let { n -> byNumber[n]?.let { f.selected = it.selected } } }
                    awaitingReadSince = System.currentTimeMillis()
                    vm.readPortal(planItems(), sessionAt())
                },
                onEditCompany = { confirmProposal = null; navigator.navigate(Routes.COMPANIES) },
                onDismiss = { confirmProposal = null },
                onConfirm = { chosen, decl, saveDecl, updateDifferent ->
                    confirmProposal = null
                    // A seleção feita na confirmação volta para o plano (e para os campos desta tela).
                    val byNumber = chosen.associateBy { it.itemNumber }
                    items.forEach { f -> f.number.trim().toIntOrNull()?.let { n -> byNumber[n]?.let { f.selected = it.selected } } }
                    vm.startProposal(planItems(), sessionAt(), decl, saveDecl, updateDifferent)
                },
            )
        }
    }
    confirmBid?.let { text ->
        ConfirmDialog(
            title = "Armar robô de lance?", message = text,
            onConfirm = { confirmBid = null; vm.saveItems(planItems(), sessionAt()) { vm.saveBid(bidConfig(), sessionAt(), arm = true) } },
            onDismiss = { confirmBid = null }, confirmLabel = "Confirmo — armar", tone = Tone.DANGER, icon = Icons.Outlined.SmartToy,
        )
    }

    LicitaScaffold(title = tender?.label ?: "Robô", subtitle = "Robô do Comprasnet", showBack = true) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (tender == null) {
                AlertBanner("Licitação não encontrada", "Busque suas licitações no Robô ou em Pregões ao Vivo.", Tone.WARNING)
                return@Column
            }
            val active = state.runs.filter { it.active }
            if (active.isNotEmpty()) {
                DangerButton("PARAR ROBÔ", { active.forEach { vm.stop(it.id) } }, icon = Icons.Outlined.StopCircle)
            }
            LicitaCard(Modifier.fillMaxWidth()) {
                Text(tender.label, style = MaterialTheme.typography.titleMedium, color = LicitaColors.TextPrimary)
                if (tender.objectDescription.isNotBlank()) Text(tender.objectDescription, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
                Spacer(Modifier.height(6.dp))
                Text(listOf(tender.modality, tender.situation).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    com.licitaia.core.ui.components.OfficialSituationBadge(tender.situation)
                    if (com.licitaia.domain.model.OfficialSituation.fromText(tender.situation) != null) Spacer(Modifier.width(8.dp))
                    StatusBadge(if (tender.hasProposal) "Proposta no portal" else "Sem proposta no portal", if (tender.hasProposal) Tone.SUCCESS else Tone.NEUTRAL)
                    Spacer(Modifier.width(8.dp))
                    state.plan?.proposalStatus?.let { StatusBadge(it.label, proposalTone(it)) }
                }
                com.licitaia.domain.model.OfficialSituation.fromText(tender.situation)?.let { s ->
                    // Robô armado/agendado para compra suspensa/cancelada: avisa e não inicia sozinho (PortalRobotEngine).
                    Spacer(Modifier.height(8.dp))
                    AlertBanner(
                        "Compra ${s.label.lowercase()}",
                        "O portal indica que esta compra está ${s.label.lowercase()}. O robô não inicia automaticamente; " +
                            "confira no portal antes de executar qualquer ação.",
                        if (s.severity == com.licitaia.domain.model.SituationSeverity.AMBER) Tone.WARNING else Tone.DANGER,
                    )
                }
                Spacer(Modifier.height(8.dp))
                SecondaryButton("Ver no portal", { navigator.navigate(Routes.portalWeb(Portal.COMPRAS_GOV)) }, Modifier.fillMaxWidth(), icon = Icons.Outlined.Language, tone = Tone.NEUTRAL)
            }

            state.runs.take(2).forEach { run -> RunCard(run, onStop = { vm.stop(run.id) }, onContinue = { vm.continueRun(run.id) }, onSend = { vm.sendSuggestion(run.id) }) }

            // ------------------------------------------------ proposta
            SectionHeader("Proposta (item a item)", actionLabel = "Adicionar item", onAction = { items += ItemForm(number = ((items.mapNotNull { it.number.toIntOrNull() }.maxOrNull() ?: 0) + 1).toString()) })
            Text(
                "Valores preenchidos com a proposta liberada no app quando houver (nº do item do edital, quantidade, valor, marca e modelo); " +
                    "confira e edite. O piso por unidade é usado só pelo robô de lance.",
                style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted,
            )
            val sel = RobotPlanRules.selection(planItems())
            LicitaCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(sel.label, style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary)
                        Text("Total ofertado: ${Formatters.brl(sel.selectedTotal)}", style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
                        if (sel.withoutPrice.isNotEmpty()) Text("${sel.withoutPrice.size} sem preço (fora da proposta)", style = MaterialTheme.typography.labelSmall, color = LicitaColors.Yellow)
                    }
                    androidx.compose.material3.TextButton(onClick = { items.forEach { f -> f.selected = f.toPlan()?.hasPrice == true } }) { Text("Marcar todos") }
                    androidx.compose.material3.TextButton(onClick = { items.forEach { it.selected = false } }) { Text("Desmarcar") }
                }
            }
            DeclarationsHint(state.declarations) { navigator.navigate(Routes.COMPANIES) }
            val readingNow = state.runs.any { it.active && it.kind == RobotKind.LEITURA }
            LicitaCard(Modifier.fillMaxWidth()) {
                Text("Situação no portal", style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary)
                Text(
                    reading?.let { ProposalReadingRules.summary(planItems(), it, state.declarations.meEpp) }
                        ?: "Ainda não lida. Toque em “Ler situação no portal” para marcar cada item: ✓ lançado (valor), ⚠ valor diferente, ○ não cadastrado.",
                    style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary,
                )
                Spacer(Modifier.height(6.dp))
                SecondaryButton(
                    if (readingNow) "Lendo o portal…" else "Ler situação no portal",
                    { awaitingReadSince = System.currentTimeMillis(); vm.readPortal(planItems(), sessionAt()) },
                    Modifier.fillMaxWidth(), icon = Icons.Outlined.Language, tone = Tone.NEUTRAL,
                    enabled = state.canOperate && !readingNow && active.none { it.kind == RobotKind.PROPOSTA },
                )
                Text(
                    "Só lê (nada é preenchido): abre cada grupo, percorre todas as páginas e pré-seleciona apenas os itens “Proposta não cadastrada” (você pode mudar).",
                    style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted,
                )
            }
            items.forEachIndexed { index, f ->
                val marker = f.toPlan()?.let { ProposalReadingRules.marker(it, reading, state.declarations.meEpp) }
                ItemEditor(f, marker, onRemove = { items.removeAt(index) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Salvar", { vm.saveItems(planItems(), sessionAt()) }, Modifier.weight(1f), icon = Icons.Outlined.Save, enabled = state.canOperate)
                if (tender.matchedTenderId != null) {
                    SecondaryButton(
                        "Usar a proposta do app",
                        {
                            scope.launch {
                                val fresh = vm.appProposalItems(planItems())
                                if (fresh.isEmpty()) {
                                    navigator.showMessage("Nenhuma proposta do app para esta licitação.")
                                } else {
                                    items.clear(); fresh.forEach { items += ItemForm.of(it) }
                                    navigator.showMessage("Itens e valores da proposta do app carregados: revise e salve.")
                                }
                            }
                        },
                        Modifier.weight(1f), tone = Tone.NEUTRAL,
                    )
                }
            }
            val running = active.any { it.kind == RobotKind.PROPOSTA }
            PrimaryButton(
                "Soltar robô — cadastrar proposta",
                {
                    val list = planItems()
                    val errors = RobotPlanRules.proposalErrors(list) + if (list.size < items.size) listOf("Revise o número de todos os itens.") else emptyList()
                    if (errors.isNotEmpty()) navigator.showMessage(errors.first()) else confirmProposal = list
                },
                Modifier.fillMaxWidth(), enabled = state.canOperate && !running, icon = Icons.Outlined.PlayArrow, tone = Tone.WARNING,
            )
            state.plan?.proposalLog?.takeIf { it.isNotEmpty() }?.let { log ->
                LicitaCard(Modifier.fillMaxWidth()) {
                    Text("Último log do robô de proposta", style = MaterialTheme.typography.labelLarge, color = LicitaColors.TextPrimary)
                    log.takeLast(12).forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextSecondary) }
                }
            }

            // ------------------------------------------------ lance
            SectionHeader("Robô de lance (disputa)")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(BidRobotMode.MANUAL, BidRobotMode.AUTOMATICO).forEach { m ->
                    FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m.label) })
                }
            }
            Text(mode.description, style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                BidStrategy.entries.forEach { s -> FilterChip(selected = strategy == s, onClick = { strategy = s }, label = { Text(s.label, maxLines = 1) }) }
            }
            Text(strategy.description, style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Redução por lance (R$)", reduction, { reduction = it }, Modifier.weight(1f), KeyboardType.Decimal)
                Field("Decremento mínimo (R$)", decrement, { decrement = it }, Modifier.weight(1f), KeyboardType.Decimal)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Entre lances (s, mín. 20)", ownInterval, { ownInterval = it }, Modifier.weight(1f), KeyboardType.Number)
                Field("Após melhor (s, mín. 3)", afterBest, { afterBest = it }, Modifier.weight(1f), KeyboardType.Number)
                Field("Teto de lances", maxBids, { maxBids = it }, Modifier.weight(1f), KeyboardType.Number)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Edital disputa pelo valor TOTAL do item", style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextPrimary, modifier = Modifier.weight(1f))
                Switch(checked = onTotal, onCheckedChange = { onTotal = it })
            }
            Field("Data/hora da sessão (dd/mm/aaaa hh:mm)", sessionText, { sessionText = it }, Modifier.fillMaxWidth(), KeyboardType.Text)
            val plan = state.plan
            if (plan?.bidArmed == true) {
                AlertBanner(
                    "Robô de lance ARMADO (${plan.bid.mode.label})",
                    "Armado em ${Formatters.dateTime(plan.bidArmedAt)}. Sessão ${plan.sessionAt?.let(Formatters::dateTime) ?: "sem horário"}.",
                    Tone.SUCCESS,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("Desarmar", { vm.saveBid(bidConfig(), sessionAt(), arm = false) }, Modifier.weight(1f), tone = Tone.DANGER, enabled = state.canOperate)
                    PrimaryButton("Entrar na disputa agora", { vm.startBidNow() }, Modifier.weight(1f), enabled = state.canOperate && active.none { it.kind == RobotKind.LANCE })
                }
            } else {
                PrimaryButton(
                    "Armar robô de lance",
                    {
                        val p = (state.plan ?: PortalRobotPlan(state.companyId ?: 0, tender.tenderKey)).copy(items = planItems(), bid = bidConfig(), sessionAt = sessionAt())
                        val errors = RobotPlanRules.bidErrors(p)
                        if (errors.isNotEmpty()) navigator.showMessage(errors.first()) else confirmBid = RobotPlanRules.bidConfirmation(tender, p)
                    },
                    Modifier.fillMaxWidth(), enabled = state.canOperate, icon = Icons.Outlined.SmartToy, tone = Tone.DANGER,
                )
            }
            Text(
                "Regras sempre aplicadas: nunca abaixo do piso; 20 s entre nossos lances e 3 s após o melhor lance (Compras.gov.br); teto de lances; " +
                    "para se o pregoeiro suspender/encerrar, se a sessão cair ou a leitura ficar ambígua; CAPTCHA/código pausam só esta sessão.",
                style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun RunCard(run: RobotRun, onStop: () -> Unit, onContinue: () -> Unit, onSend: () -> Unit) {
    LicitaCard(Modifier.fillMaxWidth(), accent = if (run.needsUser) LicitaColors.Yellow else if (run.active) LicitaColors.Blue else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(run.kind.label, style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary, modifier = Modifier.weight(1f))
            StatusBadge(run.status.label, if (run.needsUser) Tone.WARNING else if (run.active) Tone.INFO else Tone.NEUTRAL, pulsing = run.active && !run.needsUser)
        }
        Text(run.message ?: run.step, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
        if (run.kind == RobotKind.LANCE) Text("Lances enviados: ${run.bidsSent}", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
        run.suggestion?.takeIf { run.active }?.let { s ->
            Spacer(Modifier.height(6.dp))
            AlertBanner("Sugestão — item ${s.itemNumber}: ${Formatters.brl(s.value)}", s.reason, Tone.INFO, actionLabel = "Enviar", onAction = onSend)
        }
        run.log.takeLast(6).forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted) }
        if (run.active) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (run.needsUser) SecondaryButton("Tentar de novo", onContinue, Modifier.weight(1f))
                SecondaryButton("PARAR", onStop, Modifier.weight(1f), tone = Tone.DANGER, icon = Icons.Outlined.StopCircle)
            }
        }
    }
}

@Composable
private fun ItemEditor(f: ItemForm, marker: PortalMarker?, onRemove: () -> Unit) {
    val noPrice = f.toPlan()?.hasPrice != true
    LicitaCard(Modifier.fillMaxWidth(), accent = if (noPrice) LicitaColors.Yellow else if (!f.selected) LicitaColors.TextMuted else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Checkbox(checked = f.selected && !noPrice, onCheckedChange = { f.selected = it }, enabled = !noPrice)
            Field("Item nº", f.number, { f.number = it }, Modifier.width(90.dp), KeyboardType.Number)
            Spacer(Modifier.width(8.dp))
            Text(f.description.ifBlank { "Item do portal" }, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary, maxLines = 2, modifier = Modifier.weight(1f))
            IconButton(onClick = onRemove) { Icon(Icons.Outlined.Delete, contentDescription = "Remover item") }
        }
        marker?.let { PortalMarkerText(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Quantidade", f.qty, { f.qty = it }, Modifier.weight(1f), KeyboardType.Decimal)
            Field("Valor unitário (R$)", f.price, { f.price = it }, Modifier.weight(1f), KeyboardType.Decimal)
        }
        Field("Piso por unidade p/ lances (R$)", f.floor, { f.floor = it }, Modifier.fillMaxWidth(), KeyboardType.Decimal)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Marca", f.brand, { f.brand = it }, Modifier.weight(1f))
            Field("Fabricante", f.maker, { f.maker = it }, Modifier.weight(1f))
        }
        Field("Modelo / versão", f.model, { f.model = it }, Modifier.fillMaxWidth())
        Field("Descrição detalhada", f.detail, { f.detail = it }, Modifier.fillMaxWidth(), singleLine = false)
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier, type: KeyboardType = KeyboardType.Text, singleLine: Boolean = true) {
    OutlinedTextField(
        value = value, onValueChange = onChange, modifier = modifier, singleLine = singleLine, label = { Text(label, maxLines = 1) },
        keyboardOptions = KeyboardOptions(keyboardType = type),
    )
}
