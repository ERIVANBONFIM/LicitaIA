package com.licitaia.core.data.repository

import com.licitaia.ai.api.AiGateway
import com.licitaia.core.ai.withAiCompany
import com.licitaia.core.data.db.CompanyDao
import com.licitaia.core.data.db.ProposalDao
import com.licitaia.core.data.db.TenderAnalysisDao
import com.licitaia.core.data.db.TenderDao
import com.licitaia.core.data.db.toDomain
import com.licitaia.core.data.db.toEntity
import com.licitaia.core.data.session.SessionHolder
import com.licitaia.domain.model.AuditAction
import com.licitaia.domain.model.AuditOrigin
import com.licitaia.domain.model.AuditResult
import com.licitaia.domain.model.Proposal
import com.licitaia.domain.model.ProposalStatus
import com.licitaia.domain.model.Tender
import com.licitaia.domain.model.TenderStatus
import com.licitaia.domain.proposal.OfficialProposalBuilder
import com.licitaia.domain.proposal.OfficialTenderItem
import com.licitaia.domain.repository.AuditRepository
import com.licitaia.domain.repository.ProposalDraftOutcome
import com.licitaia.domain.repository.ProposalRepository
import com.licitaia.domain.security.Permission
import com.licitaia.domain.security.Rbac
import com.licitaia.domain.util.Formatters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProposalRepositoryImpl @Inject constructor(
    private val proposalDao: ProposalDao,
    private val tenderDao: TenderDao,
    private val analysisDao: TenderAnalysisDao,
    private val companyDao: CompanyDao,
    private val officialItems: OfficialItemsLoader,
    private val gateway: AiGateway,
    private val audit: AuditRepository,
    private val holder: SessionHolder,
    private val access: RepositoryAccess,
) : ProposalRepository {

    private val userName: String get() = holder.current?.user?.name ?: "Sistema"

    override fun observeProposals(tenderId: Long): Flow<List<Proposal>> =
        proposalDao.observeByTender(tenderId).map { list -> list.filter { access.owns(it.companyId) }.map { it.toDomain() } }

    override fun observeProposal(id: Long): Flow<Proposal?> = proposalDao.observeById(id).map { it?.takeIf { access.owns(it.companyId) }?.toDomain() }

    override suspend fun getProposal(id: Long): Proposal? = proposalDao.getById(id)?.takeIf { access.owns(it.companyId) }?.toDomain()

    override suspend fun generateDraft(tenderId: Long): Result<ProposalDraftOutcome> = withContext(Dispatchers.IO) {
        val tender = tenderDao.getById(tenderId)?.toDomain()
            ?: return@withContext Result.failure(IllegalArgumentException("Licitação não encontrada."))
        access.requireCompany(tender.companyId, Permission.PREPARAR_PROPOSTA)
        val company = companyDao.getById(tender.companyId)?.toDomain()
            ?: return@withContext Result.failure(IllegalStateException("Empresa não encontrada."))
        val analysis = analysisDao.get(tenderId)?.toDomain()

        // 1) Itens OFICIAIS atuais do edital (números vêm daqui, não da IA).
        val lookup = loadOfficialItems(tender)
        val built = lookup.items.takeIf { it.isNotEmpty() }?.let {
            OfficialProposalBuilder.build(it, analysis?.priceRange, tender.estimatedValue)
        }

        // 2) IA: prazo, validade e observações (e os itens, só quando não há itens oficiais).
        val provider = withAiCompany(tender.companyId) { gateway.current() }
        val draft = runCatching { withAiCompany(tender.companyId) { provider.draftProposal(tender, analysis, company) } }.getOrElse { error ->
            if (error is CancellationException) throw error
            audit.record(
                AuditAction.GERACAO_DOCUMENTO, result = AuditResult.FALHA, origin = AuditOrigin.IA,
                portal = tender.portal, tenderNumber = tender.number, reason = error.message,
                details = "Falha ao gerar rascunho com ${provider.displayName}",
            )
            if (built == null) return@withContext Result.failure(error)
            null
        }
        access.requireCompany(tender.companyId, Permission.PREPARAR_PROPOSTA)
        val version = proposalDao.maxVersion(tenderId) + 1
        val proposal = Proposal(
            tenderId = tenderId, companyId = tender.companyId, version = version,
            items = built?.items ?: draft!!.items,
            deliveryDays = draft?.deliveryDays?.takeIf { it > 0 } ?: DEFAULT_DELIVERY_DAYS,
            validityDays = draft?.validityDays?.takeIf { it > 0 } ?: DEFAULT_VALIDITY_DAYS,
            notes = draft?.notes?.takeIf { it.isNotBlank() } ?: DEFAULT_NOTES,
            status = ProposalStatus.RASCUNHO, createdBy = userName, createdAt = System.currentTimeMillis(),
        )
        val id = proposalDao.upsert(proposal.toEntity())
        moveTender(tender.id, tender.status, TenderStatus.PROPOSTA_EM_ELABORACAO, onlyFrom = EARLY_STAGES)
        audit.record(
            AuditAction.GERACAO_DOCUMENTO, origin = AuditOrigin.IA, portal = tender.portal, tenderNumber = tender.number,
            newValue = Formatters.brl(proposal.totalValue),
            details = if (built != null) {
                "Rascunho v$version montado com ${proposal.items.size} item(ns) oficiais (${lookup.source}) e termos de ${if (draft != null) provider.displayName else "padrão"}"
            } else {
                "Rascunho v$version gerado com ${provider.displayName}"
            },
        )
        Result.success(
            ProposalDraftOutcome(
                proposal = proposal.copy(id = id),
                officialSource = lookup.source.takeIf { built != null },
                confidentialItems = built?.confidentialItems.orEmpty(),
                pricedByAnalysis = built?.fromAnalysis == true,
                warning = when {
                    built == null -> lookup.message
                    draft == null -> "A IA não respondeu: prazo, validade e observações ficaram com valores padrão."
                    else -> null
                },
            ),
        )
    }

    override suspend fun refreshOfficialValues(id: Long): Result<ProposalDraftOutcome> = withContext(Dispatchers.IO) {
        try {
            val entity = proposalDao.getById(id) ?: error("Proposta não encontrada.")
            access.requireCompany(entity.companyId, Permission.PREPARAR_PROPOSTA)
            check(entity.status == ProposalStatus.RASCUNHO || entity.status == ProposalStatus.REJEITADA) {
                "Crie uma nova versão para atualizar os valores de uma proposta já aprovada."
            }
            val tender = tenderDao.getById(entity.tenderId)?.toDomain() ?: error("Licitação não encontrada.")
            val lookup = loadOfficialItems(tender)
            if (lookup.items.isEmpty()) error(lookup.message ?: "O edital não tem itens oficiais publicados.")
            val analysis = analysisDao.get(tender.id)?.toDomain()
            val built = OfficialProposalBuilder.build(lookup.items, analysis?.priceRange, tender.estimatedValue)
            val current = entity.toDomain()
            // Marca/fabricante/modelo digitados continuam no mesmo nº de item.
            val previous = current.items.filter { it.itemNumber != null }.associateBy { it.itemNumber }
            val items = built.items.map { fresh ->
                previous[fresh.itemNumber]?.let { old -> fresh.copy(brand = old.brand, manufacturer = old.manufacturer, model = old.model) } ?: fresh
            }
            val updated = current.copy(items = items, status = ProposalStatus.RASCUNHO, pdfPath = null, approvedAt = null, approvedBy = null)
            proposalDao.update(updated.toEntity())
            audit.record(
                AuditAction.GERACAO_DOCUMENTO, portal = tender.portal, tenderNumber = tender.number,
                previousValue = Formatters.brl(current.totalValue), newValue = Formatters.brl(updated.totalValue),
                details = "Valores da proposta v${current.version} atualizados com ${items.size} item(ns) oficiais (${lookup.source})",
            )
            Result.success(
                ProposalDraftOutcome(updated, lookup.source, built.confidentialItems, built.fromAnalysis),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Itens oficiais: PNCP primeiro, depois dados abertos do Compras.gov.br ([OfficialItemsLoader]). Nunca lança (exceto cancelamento). */
    private suspend fun loadOfficialItems(tender: Tender): OfficialLookup {
        val lookup = officialItems.load(tender)
        return when (lookup.failure) {
            null -> OfficialLookup(lookup.items, lookup.source)
            OfficialItemsLookup.Failure.NO_CONTROL ->
                OfficialLookup(message = "Licitação sem número de controle PNCP: os itens foram montados pela IA. Confira com o edital.")
            OfficialItemsLookup.Failure.NO_SOURCES -> OfficialLookup(message = "Consulta de itens oficiais indisponível: itens montados pela IA.")
            OfficialItemsLookup.Failure.ERROR ->
                OfficialLookup(message = "Não foi possível ler os itens oficiais do edital (${lookup.error}). Itens montados pela IA: confira com o edital.")
            OfficialItemsLookup.Failure.EMPTY ->
                OfficialLookup(message = "O edital não tem itens publicados no PNCP. Itens montados pela IA: confira com o edital.")
        }
    }

    private class OfficialLookup(
        val items: List<OfficialTenderItem> = emptyList(),
        val source: String? = null,
        val message: String? = null,
    )

    override suspend fun saveNewVersion(proposal: Proposal): Long = withContext(Dispatchers.IO) {
        access.requireCompany(proposal.companyId, Permission.PREPARAR_PROPOSTA)
        check(tenderDao.getById(proposal.tenderId)?.companyId == proposal.companyId) { "Licitação de outra empresa." }
        val version = proposalDao.maxVersion(proposal.tenderId) + 1
        val fresh = proposal.copy(
            id = 0, version = version, status = ProposalStatus.RASCUNHO, pdfPath = null, createdBy = userName,
            createdAt = System.currentTimeMillis(), approvedBy = null, approvedAt = null, rejectionReason = null,
        )
        val id = proposalDao.upsert(fresh.toEntity())
        tenderDao.getById(proposal.tenderId)?.let { tender ->
            moveTender(tender.id, tender.status, TenderStatus.PROPOSTA_EM_ELABORACAO, onlyFrom = EARLY_STAGES + REVIEW_STAGES)
            audit.record(
                AuditAction.GERACAO_DOCUMENTO, portal = tender.portal, tenderNumber = tender.number,
                newValue = Formatters.brl(fresh.totalValue), details = "Nova versão v$version da proposta salva",
            )
        }
        id
    }

    override suspend fun update(proposal: Proposal) {
        withContext(Dispatchers.IO) {
            access.requireCompany(proposal.companyId, Permission.PREPARAR_PROPOSTA)
            val existing = proposalDao.getById(proposal.id) ?: error("Proposta não encontrada.")
            check(existing.companyId == proposal.companyId && existing.tenderId == proposal.tenderId) { "Não é permitido mudar a empresa da proposta." }
            check(existing.status == ProposalStatus.RASCUNHO || existing.status == ProposalStatus.REJEITADA) { "Crie uma nova versão para editar uma proposta aprovada." }
            // PDF antigo não corresponde mais aos valores editados: será gerado de novo.
            proposalDao.update(proposal.copy(status = ProposalStatus.RASCUNHO, approvedAt = null, approvedBy = null, pdfPath = null).toEntity())
        }
    }

    override suspend fun submitForReview(id: Long) {
        withContext(Dispatchers.IO) {
            val proposal = proposalDao.getById(id) ?: return@withContext
            access.requireCompany(proposal.companyId, Permission.PREPARAR_PROPOSTA)
            val pending = proposal.toDomain().pendingPriceItems
            check(pending.isEmpty()) {
                "Defina o preço unitário de ${pending.size} item(ns) (orçamento sigiloso) antes de enviar para revisão."
            }
            proposalDao.update(proposal.copy(status = ProposalStatus.EM_REVISAO))
            tenderDao.getById(proposal.tenderId)?.let { tender ->
                moveTender(tender.id, tender.status, TenderStatus.AGUARDANDO_APROVACAO, onlyFrom = EARLY_STAGES + REVIEW_STAGES)
                audit.record(
                    AuditAction.GERACAO_DOCUMENTO, portal = tender.portal, tenderNumber = tender.number,
                    previousValue = proposal.status.label, newValue = ProposalStatus.EM_REVISAO.label,
                    details = "Proposta v${proposal.version} enviada para aprovação",
                )
            }
        }
    }

    override suspend fun approve(id: Long) {
        withContext(Dispatchers.IO) {
            val proposal = proposalDao.getById(id) ?: return@withContext
            access.requireCompany(proposal.companyId)
            val tender = tenderDao.getById(proposal.tenderId)
            if (!allowed(Permission.APROVAR_PROPOSTA)) {
                audit.record(
                    AuditAction.APROVACAO, result = AuditResult.BLOQUEADO, portal = tender?.portal,
                    tenderNumber = tender?.number, reason = "Perfil sem permissão para aprovar propostas",
                    details = "Proposta v${proposal.version}",
                )
                return@withContext
            }
            val now = System.currentTimeMillis()
            proposalDao.update(
                proposal.copy(status = ProposalStatus.APROVADA, approvedBy = userName, approvedAt = now, rejectionReason = null),
            )
            tender?.let {
                val target = if (proposal.pdfPath != null) TenderStatus.PRONTA_PARA_ENVIO else TenderStatus.APROVADA
                moveTender(it.id, it.status, target, onlyFrom = EARLY_STAGES + REVIEW_STAGES + TenderStatus.APROVADA)
                audit.record(
                    AuditAction.APROVACAO, portal = it.portal, tenderNumber = it.number,
                    newValue = Formatters.brl(proposal.toDomain().totalValue), details = "Proposta v${proposal.version} aprovada",
                )
            }
        }
    }

    override suspend fun reject(id: Long, reason: String) {
        withContext(Dispatchers.IO) {
            val proposal = proposalDao.getById(id) ?: return@withContext
            access.requireCompany(proposal.companyId)
            val tender = tenderDao.getById(proposal.tenderId)
            if (!allowed(Permission.APROVAR_PROPOSTA)) {
                audit.record(
                    AuditAction.REJEICAO, result = AuditResult.BLOQUEADO, portal = tender?.portal,
                    tenderNumber = tender?.number, reason = "Perfil sem permissão para rejeitar propostas",
                )
                return@withContext
            }
            proposalDao.update(proposal.copy(status = ProposalStatus.REJEITADA, rejectionReason = reason.trim()))
            tender?.let {
                moveTender(it.id, it.status, TenderStatus.PROPOSTA_EM_ELABORACAO, onlyFrom = REVIEW_STAGES + TenderStatus.APROVADA)
                audit.record(
                    AuditAction.REJEICAO, portal = it.portal, tenderNumber = it.number, reason = reason.trim(),
                    details = "Proposta v${proposal.version} rejeitada",
                )
            }
        }
    }

    override suspend fun attachPdf(id: Long, path: String) {
        withContext(Dispatchers.IO) {
            val proposal = proposalDao.getById(id) ?: return@withContext
            access.requireCompany(proposal.companyId, Permission.PREPARAR_PROPOSTA)
            proposalDao.update(proposal.copy(pdfPath = path))
            tenderDao.getById(proposal.tenderId)?.let { tender ->
                if (proposal.status == ProposalStatus.APROVADA) {
                    moveTender(tender.id, tender.status, TenderStatus.PRONTA_PARA_ENVIO, onlyFrom = setOf(TenderStatus.APROVADA))
                }
                audit.record(
                    AuditAction.GERACAO_DOCUMENTO, portal = tender.portal, tenderNumber = tender.number,
                    details = "PDF da proposta v${proposal.version} gerado",
                )
            }
        }
    }

    /**
     * Libera a proposta APROVADA para o portal (status "Liberada para o portal"). Nada sai do aparelho aqui: o cadastro
     * no Compras.gov.br é feito depois pelo robô, com nova confirmação do usuário.
     */
    override suspend fun simulateSubmission(id: Long): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val proposal = proposalDao.getById(id) ?: error("Proposta não encontrada.")
            access.requireCompany(proposal.companyId, Permission.APROVAR_ENVIO)
            if (!allowed(Permission.APROVAR_ENVIO)) error("Seu perfil não pode liberar propostas para o portal.")
            check(proposal.status == ProposalStatus.APROVADA || proposal.status == ProposalStatus.ENVIADA_SIMULADA) {
                "Somente propostas aprovadas podem ser liberadas para o portal."
            }
            val domain = proposal.toDomain()
            check(domain.pendingPriceItems.isEmpty()) {
                "Defina o preço dos itens ${domain.pendingPriceItems.joinToString { (it.itemNumber ?: 0).toString() }} antes de liberar."
            }
            if (proposal.status != ProposalStatus.ENVIADA_SIMULADA) proposalDao.update(proposal.copy(status = ProposalStatus.ENVIADA_SIMULADA))
            tenderDao.getById(proposal.tenderId)?.let { tender ->
                moveTender(
                    tender.id, tender.status, TenderStatus.ENVIADA_SIMULADA,
                    onlyFrom = EARLY_STAGES + REVIEW_STAGES + TenderStatus.APROVADA,
                )
                audit.record(
                    AuditAction.ENVIO, portal = tender.portal, tenderNumber = tender.number,
                    previousValue = proposal.status.label, newValue = Formatters.brl(domain.totalValue),
                    details = "Proposta v${proposal.version} liberada para o portal (cadastro pelo robô após nova confirmação)",
                )
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun observePendingApprovals(companyId: Long): Flow<Int> =
        proposalDao.observeCountByStatus(companyId, ProposalStatus.EM_REVISAO).map { if (access.owns(companyId)) it else 0 }.distinctUntilChanged()

    // ------------------------------------------------------------------ apoio

    private fun allowed(permission: Permission): Boolean {
        val role = holder.current?.user?.role ?: return false
        return Rbac.can(role, permission)
    }

    private suspend fun moveTender(tenderId: Long, current: TenderStatus, target: TenderStatus, onlyFrom: Set<TenderStatus>) {
        if (current != target && current in onlyFrom) tenderDao.updateStatus(tenderId, target, System.currentTimeMillis())
    }

    private companion object {
        const val DEFAULT_DELIVERY_DAYS = 30
        const val DEFAULT_VALIDITY_DAYS = 60
        const val DEFAULT_NOTES = "Nos preços propostos estão incluídos todos os tributos, encargos sociais, trabalhistas e fiscais, " +
            "fretes, seguros e demais despesas necessárias à execução do objeto."
        val EARLY_STAGES = setOf(TenderStatus.INTERESSE, TenderStatus.EM_ANALISE, TenderStatus.ANALISADA, TenderStatus.PROPOSTA_EM_ELABORACAO)
        val REVIEW_STAGES = setOf(TenderStatus.AGUARDANDO_APROVACAO, TenderStatus.PRONTA_PARA_ENVIO)
    }
}
