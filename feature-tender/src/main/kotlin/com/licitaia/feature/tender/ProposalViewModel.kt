package com.licitaia.feature.tender

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.licitaia.core.ui.nav.Routes
import com.licitaia.domain.model.AuditAction
import com.licitaia.domain.model.AuditResult
import com.licitaia.domain.model.AuthSession
import com.licitaia.domain.model.Company
import com.licitaia.domain.model.Proposal
import com.licitaia.domain.model.ProposalItem
import com.licitaia.domain.model.ProposalStatus
import com.licitaia.domain.model.Tender
import com.licitaia.domain.model.TenderAnalysis
import com.licitaia.domain.model.TenderStatus
import com.licitaia.domain.model.pncpControlNumber
import com.licitaia.domain.portal.PortalRobotPlan
import com.licitaia.domain.portal.PortalRobotRepository
import com.licitaia.domain.portal.ProposalRobotMapping
import com.licitaia.domain.portal.RobotProposalStatus
import com.licitaia.domain.proposal.OfficialProposalBuilder
import com.licitaia.domain.repository.AuditRepository
import com.licitaia.domain.repository.ProposalDraftOutcome
import com.licitaia.domain.util.Formatters
import com.licitaia.domain.util.missingProposalData
import com.licitaia.domain.repository.AuthRepository
import com.licitaia.domain.repository.CompanyRepository
import com.licitaia.domain.repository.ProposalPdfGenerator
import com.licitaia.domain.repository.ProposalRepository
import com.licitaia.domain.repository.TenderRepository
import com.licitaia.domain.security.Permission
import com.licitaia.domain.security.Rbac
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ItemDraft(
    val description: String = "",
    val unit: String = "un",
    val quantity: String = "1",
    val unitPrice: String = "",
    /** Nº do item no edital (texto do campo; vazio = item livre). */
    val itemNumber: String = "",
    val brand: String = "",
    val manufacturer: String = "",
    val model: String = "",
    /** Valor unitário estimado pelo órgão (teto), só leitura; null = sigiloso/desconhecido. */
    val estimatedUnitPrice: Double? = null,
    val confidentialBudget: Boolean = false,
) {
    /** Preço digitado acima do estimado pelo órgão (aviso, não bloqueia). */
    val aboveEstimate: Boolean
        get() {
            val p = parseNumber(unitPrice) ?: return false
            return estimatedUnitPrice != null && p > estimatedUnitPrice + 1e-9
        }

    /** Sem preço definido (ex.: orçamento sigiloso). */
    val priceMissing: Boolean get() = (parseNumber(unitPrice) ?: 0.0).let { it.isNaN() || it <= 0.0 }

    val total: Double
        get() {
            val q = parseNumber(quantity) ?: return 0.0
            val p = parseNumber(unitPrice) ?: return 0.0
            return if (q.isNaN() || p.isNaN()) 0.0 else q * p
        }
}

data class ProposalDraft(
    val items: List<ItemDraft> = listOf(ItemDraft()),
    val deliveryDays: String = "30",
    val validityDays: String = "60",
    val notes: String = "",
) {
    val total: Double get() = items.sumOf { it.total }

    companion object {
        fun from(p: Proposal) = ProposalDraft(
            items = p.items.map {
                ItemDraft(
                    description = it.description, unit = it.unit, quantity = numberText(it.quantity),
                    unitPrice = if (it.unitPrice > 0.0) numberText(it.unitPrice) else "",
                    itemNumber = it.itemNumber?.toString().orEmpty(), brand = it.brand, manufacturer = it.manufacturer, model = it.model,
                    estimatedUnitPrice = it.estimatedUnitPrice, confidentialBudget = it.confidentialBudget,
                )
            }.ifEmpty { listOf(ItemDraft()) },
            deliveryDays = p.deliveryDays.toString(),
            validityDays = p.validityDays.toString(),
            notes = p.notes,
        )
    }
}

sealed interface ProposalEvent {
    data class Message(val text: String) : ProposalEvent
    data class Navigate(val route: String) : ProposalEvent
    data class Share(val path: String, val title: String) : ProposalEvent
}

private data class Remote(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val session: AuthSession? = null,
    val tender: Tender? = null,
    val analysis: TenderAnalysis? = null,
    val versions: List<Proposal> = emptyList(),
)

private data class Local(
    val selectedId: Long? = null,
    val draft: ProposalDraft = ProposalDraft(),
    val dirty: Boolean = false,
    /** Ação em andamento (rótulo), ou null. */
    val busy: String? = null,
    val validation: String? = null,
)

data class ProposalUiState(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val tender: Tender? = null,
    val analysis: TenderAnalysis? = null,
    val versions: List<Proposal> = emptyList(),
    val selected: Proposal? = null,
    /** Versão imediatamente anterior à selecionada (para comparação). */
    val previous: Proposal? = null,
    val draft: ProposalDraft = ProposalDraft(),
    val dirty: Boolean = false,
    val busy: String? = null,
    val validation: String? = null,
    val userName: String = "",
    val roleLabel: String = "",
    val canPrepare: Boolean = false,
    val canApprove: Boolean = false,
    val canSubmit: Boolean = false,
    val companyName: String = "",
    /** Dados essenciais do PDF que faltam no cadastro da empresa (ex.: "endereço", "representante legal"). */
    val missingCompanyData: List<String> = emptyList(),
) {
    /** Dono/Admin: pode preparar, aprovar e liberar → atalho "Aprovar e liberar para o portal". */
    val canFastTrack: Boolean get() = canPrepare && canApprove && canSubmit

    /** A licitação tem número de controle PNCP (itens oficiais consultáveis). */
    val hasOfficialItems: Boolean get() = tender?.pncpControlNumber != null
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ProposalViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val auth: AuthRepository,
    private val tenders: TenderRepository,
    private val proposals: ProposalRepository,
    private val companies: CompanyRepository,
    private val pdfGenerator: ProposalPdfGenerator,
    private val audit: AuditRepository,
    private val robots: PortalRobotRepository,
) : ViewModel() {

    private val tenderId: Long = savedStateHandle.longArg("tenderId") ?: -1L
    private val local = MutableStateFlow(Local())

    private val _events = MutableSharedFlow<ProposalEvent>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    private val remote = auth.session.flatMapLatest { session ->
        if (session == null || tenderId <= 0) {
            flowOf(Remote(loading = false, notFound = true))
        } else {
            combine(
                tenders.observeTender(tenderId),
                tenders.observeAnalysis(tenderId).catch { emit(null) },
                proposals.observeProposals(tenderId).catch { emit(emptyList()) },
            ) { tender, analysis, versions ->
                if (tender == null || tender.companyId != session.activeCompany.id) {
                    Remote(loading = false, notFound = true)
                } else {
                    Remote(loading = false, session = session, tender = tender, analysis = analysis, versions = versions.sortedByDescending { it.version })
                }
            }.catch { emit(Remote(loading = false, notFound = true)) }
        }
    }

    val state: StateFlow<ProposalUiState> = combine(remote, local) { r, l ->
        val selected = r.versions.firstOrNull { it.id == l.selectedId } ?: r.versions.firstOrNull()
        val role = r.session?.user?.role
        ProposalUiState(
            loading = r.loading,
            notFound = r.notFound,
            tender = r.tender,
            analysis = r.analysis,
            versions = r.versions,
            selected = selected,
            previous = selected?.let { s -> r.versions.filter { it.version < s.version }.maxByOrNull { it.version } },
            draft = l.draft,
            dirty = l.dirty,
            busy = l.busy,
            validation = l.validation,
            userName = r.session?.user?.name.orEmpty(),
            roleLabel = role?.label.orEmpty(),
            canPrepare = role?.let { Rbac.can(it, Permission.PREPARAR_PROPOSTA) } ?: false,
            canApprove = role?.let { Rbac.can(it, Permission.APROVAR_PROPOSTA) } ?: false,
            canSubmit = role?.let { Rbac.can(it, Permission.APROVAR_ENVIO) } ?: false,
            companyName = r.session?.activeCompany?.let { it.tradeName.ifBlank { it.name } }.orEmpty(),
            missingCompanyData = r.session?.activeCompany?.missingProposalData().orEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProposalUiState())

    init {
        // Mantém o rascunho sincronizado com a versão selecionada enquanto não houver edição local.
        viewModelScope.launch {
            remote.collect { r ->
                local.update { l ->
                    val selected = r.versions.firstOrNull { it.id == l.selectedId } ?: r.versions.firstOrNull()
                    when {
                        selected == null -> l.copy(selectedId = null)
                        l.selectedId != selected.id -> l.copy(selectedId = selected.id, draft = ProposalDraft.from(selected), dirty = false, validation = null)
                        !l.dirty -> l.copy(draft = ProposalDraft.from(selected))
                        else -> l
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ edição local

    fun select(id: Long) {
        val proposal = state.value.versions.firstOrNull { it.id == id } ?: return
        local.update { it.copy(selectedId = id, draft = ProposalDraft.from(proposal), dirty = false, validation = null) }
    }

    fun editDraft(transform: (ProposalDraft) -> ProposalDraft) =
        local.update { it.copy(draft = transform(it.draft), dirty = true, validation = null) }

    fun editItem(index: Int, transform: (ItemDraft) -> ItemDraft) = editDraft { d ->
        d.copy(items = d.items.mapIndexed { i, item -> if (i == index) transform(item) else item })
    }

    fun addItem() = editDraft { d ->
        val next = d.items.mapNotNull { it.itemNumber.trim().toIntOrNull() }.maxOrNull()?.plus(1)
        d.copy(items = d.items + ItemDraft(itemNumber = next?.toString().orEmpty()))
    }

    fun removeItem(index: Int) = editDraft { d ->
        if (d.items.size <= 1) d else d.copy(items = d.items.filterIndexed { i, _ -> i != index })
    }

    fun discardChanges() {
        val selected = state.value.selected ?: return
        local.update { it.copy(draft = ProposalDraft.from(selected), dirty = false, validation = null) }
    }

    // ------------------------------------------------------------------ ações

    fun generateWithAi() = run("Gerando proposta com os itens do edital…") {
        val outcome = proposals.generateDraft(tenderId).getOrThrow()
        val result = outcome.proposal
        local.update { it.copy(selectedId = result.id, draft = ProposalDraft.from(result), dirty = false) }
        advanceTender(TenderStatus.PROPOSTA_EM_ELABORACAO)
        message(outcomeMessage(outcome, "Rascunho v${result.version}"))
    }

    /** Relê os itens oficiais do edital e recalcula os valores do rascunho selecionado. */
    fun refreshOfficialValues() = withSelected("Atualizando valores do edital…") { p ->
        check(!local.value.dirty) { "Salve ou descarte as alterações antes de atualizar os valores." }
        val outcome = proposals.refreshOfficialValues(p.id).getOrThrow()
        local.update { it.copy(selectedId = outcome.proposal.id, draft = ProposalDraft.from(outcome.proposal), dirty = false) }
        message(outcomeMessage(outcome, "Valores da v${p.version} atualizados"))
    }

    private fun outcomeMessage(outcome: ProposalDraftOutcome, prefix: String): String = buildString {
        val p = outcome.proposal
        if (outcome.officialSource != null) {
            append("$prefix com ${p.items.size} item(ns) oficiais do edital (${outcome.officialSource}). ")
            append(if (outcome.pricedByAnalysis) "Preços pela faixa da análise da IA, nunca acima do estimado. " else "Preços ${Formatters.percent(OfficialProposalBuilder.DEFAULT_DISCOUNT_PCT)} abaixo do estimado. ")
            if (outcome.confidentialItems.isNotEmpty()) append("Orçamento sigiloso nos itens ${outcome.confidentialItems.joinToString()}: defina o preço. ")
        } else {
            append("$prefix gerado pela IA. ")
        }
        outcome.warning?.let { append(it).append(' ') }
        append("Revise e edite antes de enviar.")
    }

    fun startBlank() = run("Criando rascunho…") {
        val s = state.value
        val tender = s.tender ?: error("Licitação não carregada")
        val blank = Proposal(
            tenderId = tenderId, companyId = tender.companyId,
            items = listOf(ProposalItem(OfficialProposalBuilder.summarize(tender.objectDescription, 200), "un", 1.0, s.analysis?.priceRange?.suggested ?: 0.0, itemNumber = 1)),
            deliveryDays = 30, validityDays = 60, createdBy = s.userName.ifBlank { "Usuário" },
        )
        val id = proposals.saveNewVersion(blank)
        local.update { it.copy(selectedId = id, dirty = false) }
        advanceTender(TenderStatus.PROPOSTA_EM_ELABORACAO)
        message("Rascunho criado. Preencha os itens da proposta.")
    }

    /** Salva as edições na própria versão (apenas rascunhos). */
    fun saveDraft() {
        val built = buildFromDraft() ?: return
        run("Salvando…") {
            proposals.update(built)
            local.update { it.copy(dirty = false) }
            message("Rascunho v${built.version} salvo.")
        }
    }

    /** Salva as edições como NOVA versão (version + 1, status RASCUNHO). */
    fun saveNewVersion() {
        val built = buildFromDraft() ?: return
        run("Criando nova versão…") {
            val id = proposals.saveNewVersion(built.copy(createdBy = state.value.userName.ifBlank { built.createdBy }))
            local.update { it.copy(selectedId = id, dirty = false) }
            advanceTender(TenderStatus.PROPOSTA_EM_ELABORACAO)
            message("Nova versão criada a partir da v${built.version}.")
        }
    }

    fun submitForReview() = withSelected("Enviando para revisão…") { p ->
        proposals.submitForReview(p.id)
        advanceTender(TenderStatus.AGUARDANDO_APROVACAO)
        message("Proposta v${p.version} enviada para revisão.")
    }

    fun approve() = withSelected("Aprovando…") { p ->
        proposals.approve(p.id)
        advanceTender(TenderStatus.APROVADA)
        message("Proposta v${p.version} aprovada.")
    }

    fun reject(reason: String) = withSelected("Rejeitando…") { p ->
        proposals.reject(p.id, reason.trim())
        advanceTender(TenderStatus.PROPOSTA_EM_ELABORACAO)
        message("Proposta v${p.version} rejeitada. O responsável pode gerar uma nova versão.")
    }

    /** Liberação ao portal — chamada somente após o BindingConfirmDialog. */
    fun simulateSubmission() = withSelected("Liberando proposta…") { p -> release(p) }

    /**
     * Atalho do dono (perfil que pode preparar, aprovar e liberar): revisão + aprovação + liberação de uma vez,
     * chamado somente após a mesma confirmação explícita (BindingConfirmDialog). Outros perfis seguem o fluxo completo.
     */
    fun approveAndRelease() = withSelected("Aprovando e liberando…") { p ->
        val s = state.value
        check(s.canFastTrack) { "Seu perfil não pode aprovar e liberar sozinho: use o fluxo de revisão." }
        check(!s.dirty) { "Salve as alterações antes de aprovar." }
        check(p.pendingPriceItems.isEmpty()) { "Defina o preço de todos os itens antes de aprovar." }
        if (p.status == ProposalStatus.RASCUNHO || p.status == ProposalStatus.REJEITADA) proposals.submitForReview(p.id)
        if (p.status != ProposalStatus.APROVADA) proposals.approve(p.id)
        val approved = proposals.getProposal(p.id)
        check(approved?.status == ProposalStatus.APROVADA) { "Não foi possível aprovar a proposta (verifique o perfil)." }
        advanceTender(TenderStatus.APROVADA)
        release(approved!!)
    }

    private suspend fun release(p: Proposal) {
        proposals.simulateSubmission(p.id).getOrThrow()
        advanceTender(TenderStatus.ENVIADA_SIMULADA)
        val prefilled = try {
            prefillRobotPlan(p)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        message(
            if (prefilled) "Proposta liberada. Itens e valores já estão no plano do robô: revise e toque em “Soltar robô”."
            else "Proposta liberada. Abrindo o robô: os itens da proposta preenchem o plano para você revisar.",
        )
        _events.tryEmit(ProposalEvent.Navigate("robotproposal/$tenderId"))
    }

    /**
     * Grava os itens da proposta liberada no plano do robô quando a licitação já tem vínculo em "Minhas licitações"
     * (sem vínculo, o plano lê a proposta liberada ao abrir). Não mexe em cadastro em andamento.
     */
    private suspend fun prefillRobotPlan(p: Proposal): Boolean {
        val tender = state.value.tender ?: return false
        val mine = robots.getMyTenders(tender.companyId).firstOrNull { it.matchedTenderId == tender.id } ?: return false
        val existing = robots.getPlan(tender.companyId, mine.tenderKey)
        if (existing?.proposalStatus == RobotProposalStatus.EXECUTANDO || existing?.proposalStatus == RobotProposalStatus.AGUARDANDO_USUARIO) return false
        val items = ProposalRobotMapping.mergeFloors(ProposalRobotMapping.toPlanItems(p), existing?.items.orEmpty())
        val base = existing ?: PortalRobotPlan(
            companyId = tender.companyId, tenderKey = mine.tenderKey,
            sessionAt = mine.openingAt ?: tender.sessionAt.takeIf { it > 0 },
        )
        val status = when (base.proposalStatus) {
            RobotProposalStatus.NAO_CONFIGURADA, RobotProposalStatus.FALHOU, RobotProposalStatus.PRONTA -> RobotProposalStatus.PRONTA
            else -> base.proposalStatus
        }
        robots.savePlan(base.copy(items = items, proposalStatus = status))
        return true
    }

    fun generatePdf(openAfter: Boolean = true) = withSelected("Gerando PDF…") { p ->
        buildPdf(p)
        if (openAfter) _events.tryEmit(ProposalEvent.Navigate(Routes.proposalPdf(p.id))) else message("PDF gerado.")
    }

    fun sharePdf() = withSelected("Preparando compartilhamento…") { p ->
        val path = p.pdfPath?.takeIf { java.io.File(it).let { f -> f.exists() && f.length() > 0 && f.name.startsWith("Proposta_") } } ?: buildPdf(p)
        _events.tryEmit(ProposalEvent.Share(path, "Proposta ${state.value.tender?.number.orEmpty()} v${p.version}"))
    }

    // ------------------------------------------------------------------ internos

    private suspend fun buildPdf(p: Proposal): String {
        val tender = state.value.tender ?: error("Licitação não carregada")
        val company = resolveCompany(tender)
        val path = pdfGenerator.generate(p, tender, company).getOrThrow()
        proposals.attachPdf(p.id, path)
        try {
            audit.record(
                action = AuditAction.GERACAO_DOCUMENTO,
                portal = tender.portal,
                tenderNumber = tender.number,
                item = "Proposta v${p.version}",
                newValue = path.substringAfterLast('/'),
                details = "PDF da proposta comercial gerado (${p.items.size} itens, total ${com.licitaia.domain.util.Formatters.brl(p.totalValue)}).",
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // auditoria não deve impedir a entrega do PDF
        }
        return path
    }

    private suspend fun resolveCompany(tender: Tender): Company {
        val session = auth.session.value
        if (session != null && session.activeCompany.id == tender.companyId) return session.activeCompany
        return companies.getCompany(tender.companyId) ?: error("Empresa da licitação não encontrada")
    }

    private fun buildFromDraft(): Proposal? {
        val s = state.value
        val selected = s.selected ?: run { local.update { it.copy(validation = "Nenhuma versão selecionada.") }; return null }
        val d = s.draft
        val items = d.items.mapIndexed { index, item ->
            val q = parseNumber(item.quantity)
            // preço vazio = a definir (ex.: orçamento sigiloso); bloqueado só ao enviar para revisão
            val p = if (item.unitPrice.isBlank()) 0.0 else parseNumber(item.unitPrice)
            val number = item.itemNumber.trim().takeIf { it.isNotEmpty() }?.let { it.toIntOrNull() ?: -1 }
            when {
                item.description.isBlank() -> return invalid("Item ${index + 1}: informe a descrição.")
                item.unit.isBlank() -> return invalid("Item ${index + 1}: informe a unidade.")
                number != null && number <= 0 -> return invalid("Item ${index + 1}: nº do item do edital inválido.")
                q == null || q.isNaN() || q <= 0 -> return invalid("Item ${index + 1}: quantidade inválida.")
                p == null || p.isNaN() || p < 0 -> return invalid("Item ${index + 1}: preço unitário inválido.")
                else -> ProposalItem(
                    description = item.description.trim(), unit = item.unit.trim(), quantity = q, unitPrice = p,
                    itemNumber = number, brand = item.brand.trim(), manufacturer = item.manufacturer.trim(), model = item.model.trim(),
                    estimatedUnitPrice = item.estimatedUnitPrice, confidentialBudget = item.confidentialBudget,
                )
            }
        }
        if (items.isEmpty()) return invalid("Inclua ao menos um item.")
        items.mapNotNull { it.itemNumber }.groupBy { it }.filter { it.value.size > 1 }.keys.firstOrNull()?.let {
            return invalid("O item $it do edital aparece mais de uma vez.")
        }
        val delivery = d.deliveryDays.trim().toIntOrNull() ?: return invalid("Prazo de entrega inválido.")
        if (delivery <= 0) return invalid("O prazo de entrega deve ser maior que zero.")
        val validity = d.validityDays.trim().toIntOrNull() ?: return invalid("Validade inválida.")
        if (validity <= 0) return invalid("A validade deve ser maior que zero.")
        if (items.sumOf { it.total } <= 0.0) return invalid("O valor total da proposta deve ser maior que zero.")
        return selected.copy(items = items, deliveryDays = delivery, validityDays = validity, notes = d.notes.trim())
    }

    private fun invalid(message: String): Proposal? {
        local.update { it.copy(validation = message) }
        return null
    }

    private suspend fun advanceTender(status: TenderStatus) {
        val tender = state.value.tender ?: return
        // não regride licitações já em disputa/decididas; status idempotente caso o repositório também atualize
        if (tender.status in FROZEN || tender.status == status) return
        try {
            tenders.updateStatus(tender.id, status)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private fun withSelected(label: String, block: suspend (Proposal) -> Unit) {
        val selected = state.value.selected ?: run { message("Nenhuma versão selecionada."); return }
        run(label) { block(selected) }
    }

    private fun run(label: String, block: suspend () -> Unit) {
        if (local.value.busy != null) return
        local.update { it.copy(busy = label, validation = null) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message(e.message?.takeIf(String::isNotBlank) ?: "Não foi possível concluir a ação.")
            } finally {
                local.update { it.copy(busy = null) }
            }
        }
    }

    private fun message(text: String) {
        _events.tryEmit(ProposalEvent.Message(text))
    }

    private companion object {
        val FROZEN = setOf(TenderStatus.EM_DISPUTA, TenderStatus.VENCIDA, TenderStatus.PERDIDA, TenderStatus.DESCARTADA)
    }
}
