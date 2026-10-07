package com.licitaia.core.data.repository

import com.licitaia.core.data.db.OpportunityDao
import com.licitaia.core.data.db.OpportunityFlagDao
import com.licitaia.core.data.db.TenderDao
import com.licitaia.core.data.db.toDomain
import com.licitaia.domain.model.ArchiveRepository
import com.licitaia.domain.model.ArchiveRules
import com.licitaia.domain.model.ArchivedEntry
import com.licitaia.domain.model.AuditAction
import com.licitaia.domain.repository.AuditRepository
import com.licitaia.domain.repository.OpportunityFlagsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** "Licitações arquivadas": marca `opportunity_flags.discardedAt` (v14) + licitações de interesse + cache local. */
@Singleton
class ArchiveRepositoryImpl @Inject constructor(
    private val flagDao: OpportunityFlagDao,
    private val tenderDao: TenderDao,
    private val opportunityDao: OpportunityDao,
    private val flags: OpportunityFlagsRepository,
    private val access: RepositoryAccess,
    private val audit: AuditRepository,
) : ArchiveRepository {

    override fun observeArchivedIds(companyId: Long): Flow<Set<String>> =
        flagDao.observe(companyId).map { rows -> if (!access.owns(companyId)) emptySet() else rows.filter { it.discardedAt != null }.mapTo(HashSet()) { it.opportunityId } }
            .distinctUntilChanged()

    override fun observeArchived(companyId: Long): Flow<List<ArchivedEntry>> =
        combine(flagDao.observe(companyId), tenderDao.observeByCompany(companyId)) { rows, tenders ->
            if (!access.owns(companyId)) return@combine emptyList()
            val archived = rows.filter { it.discardedAt != null }
            val byOpp = tenders.associateBy { it.opportunityId }
            val cached = archived.map { it.opportunityId }.filter { it !in byOpp }.chunked(500)
                .flatMap { opportunityDao.getByIds(it) }.associateBy { it.id }
            ArchiveRules.sort(
                archived.map { f ->
                    ArchivedEntry(f.opportunityId, f.discardedAt ?: 0L, byOpp[f.opportunityId]?.toDomain(), cached[f.opportunityId]?.toDomain())
                },
            )
        }.flowOn(Dispatchers.IO)

    override suspend fun archive(companyId: Long, opportunityId: String) {
        flags.discard(companyId, opportunityId)
        runCatching { audit.record(AuditAction.CADASTRO, details = "Licitação arquivada ($opportunityId)") }
    }

    override suspend fun unarchive(companyId: Long, opportunityId: String) {
        flags.restore(companyId, opportunityId)
        runCatching { audit.record(AuditAction.CADASTRO, details = "Licitação desarquivada ($opportunityId)") }
    }
}
