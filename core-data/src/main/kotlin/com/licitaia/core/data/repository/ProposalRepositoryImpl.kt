package com.licitaia.core.data.repository

import com.licitaia.ai.api.AiGateway
import com.licitaia.connector.api.ConnectorRegistry
import com.licitaia.connector.api.HumanConfirmation
import com.licitaia.connector.api.SubmissionResult
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
import com.licitaia.domain.model.TenderStatus
import com.licitaia.domain.repository.AuditRepository
import com.licitaia.domain.repository.ProposalRepository
import com.licitaia.domain.security.Permission
import com.licitaia.domain.security.Rbac
import com.licitaia.domain.util.Formatters
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
    private val registry: ConnectorRegistry,
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

    override suspend fun generateDraft(tenderId: Long): Result<Proposal> = withContext(Dispatchers.IO) {
        val tender = tenderDao.getById(tenderId)?.toDomain()
            ?: return@withContext Result.failure(IllegalArgumentException("Licitação não encontrada."))
        access.requireCompany(tender.companyId, Permission.PREPARAR_PROPOSTA)
        val company = companyDao.getById(tender.companyId)?.toDomain()
            ?: return@withContext Result.failure(IllegalStateException("Empresa não encontrada."))
        val analysis = analysisDao.get(tenderId)?.toDomain()
        val provider = gateway.current()
        val draft = runCatching { provider.draftProposal(tender, analysis, company) }.getOrElse { error ->
            audit.record(
                AuditAction.GERACAO_DOCUMENTO, result = AuditResult.FALHA, origin = AuditOrigin.IA,
                portal = tender.portal, tenderNumber = tender.number, reason = error.message,
                details = "Falha ao gerar rascunho com ${provider.displayName}",
            )
            return@withContext Result.failure(error)
        }
        access.requireCompany(tender.companyId, Permission.PREPARAR_PROPOSTA)
        val version = proposalDao.maxVersion(tenderId) + 1
        val proposal = Proposal(
            tenderId = tenderId, companyId = tender.companyId, version = version, items = draft.items,
            deliveryDays = draft.deliveryDays, validityDays = draft.validityDays, notes = draft.notes,
            status = ProposalStatus.RASCUNHO, createdBy = userName, createdAt = System.currentTimeMillis(),
        )
        val id = proposalDao.upsert(proposal.toEntity())
        moveTender(tender.id, tender.status, TenderStatus.PROPOSTA_EM_ELABORACAO, onlyFrom = EARLY_STAGES)
        audit.record(
            AuditAction.GERACAO_DOCUMENTO, origin = AuditOrigin.IA, portal = tender.portal, tenderNumber = tender.number,
            newValue = Formatters.brl(proposal.totalValue), details = "Rascunho v$version gerado com ${provider.displayName}",
        )
        Result.success(proposal.copy(id = id))
    }

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
            proposalDao.update(proposal.copy(status = ProposalStatus.RASCUNHO, approvedAt = null, approvedBy = null).toEntity())
        }
    }

    override suspend fun submitForReview(id: Long) {
        withContext(Dispatchers.IO) {
            val proposal = proposalDao.getById(id) ?: return@withContext
            access.requireCompany(proposal.companyId, Permission.PREPARAR_PROPOSTA)
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

    override suspend fun simulateSubmission(id: Long): Result<Unit> = runCatching {
        val proposal = proposalDao.getById(id) ?: error("Proposta não encontrada.")
        access.requireCompany(proposal.companyId, Permission.APROVAR_ENVIO)
        error("Envio integrado indisponível. Exporte o PDF e envie manualmente no portal oficial, conferindo a empresa e os dados.")
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
        val EARLY_STAGES = setOf(TenderStatus.INTERESSE, TenderStatus.EM_ANALISE, TenderStatus.ANALISADA, TenderStatus.PROPOSTA_EM_ELABORACAO)
        val REVIEW_STAGES = setOf(TenderStatus.AGUARDANDO_APROVACAO, TenderStatus.PRONTA_PARA_ENVIO)
    }
}
