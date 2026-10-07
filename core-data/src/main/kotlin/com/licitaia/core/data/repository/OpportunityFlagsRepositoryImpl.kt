package com.licitaia.core.data.repository

import com.licitaia.core.data.db.OpportunityFlagDao
import com.licitaia.core.data.db.OpportunityFlagEntity
import com.licitaia.core.data.db.toDomain
import com.licitaia.domain.model.OpportunityFlag
import com.licitaia.domain.repository.OpportunityFlagsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Descartadas/vistas por empresa (`opportunity_flags`) e 1ª vez no cache (`opportunity_first_seen`). */
@Singleton
class OpportunityFlagsRepositoryImpl @Inject constructor(
    private val dao: OpportunityFlagDao,
    private val access: RepositoryAccess,
) : OpportunityFlagsRepository {

    private fun clock(): Long = System.currentTimeMillis()

    override fun observeFlags(companyId: Long): Flow<Map<String, OpportunityFlag>> =
        dao.observe(companyId).map { rows -> rows.associate { it.opportunityId to it.toDomain() } }

    override suspend fun firstSeen(ids: Collection<String>): Map<String, Long> = withContext(Dispatchers.IO) {
        ids.toList().chunked(MAX_SQL_ARGS).flatMap { dao.firstSeen(it) }.associate { it.opportunityId to it.firstSeenAt }
    }

    override suspend fun discard(companyId: Long, opportunityId: String) = edit(companyId, opportunityId) { it.copy(discardedAt = clock()) }

    override suspend fun restore(companyId: Long, opportunityId: String) = edit(companyId, opportunityId) { it.copy(discardedAt = null) }

    override suspend fun markSeen(companyId: Long, opportunityId: String) =
        edit(companyId, opportunityId) { if (it.seenAt != null) it else it.copy(seenAt = clock()) }

    private suspend fun edit(companyId: Long, opportunityId: String, transform: (OpportunityFlagEntity) -> OpportunityFlagEntity) =
        withContext(Dispatchers.IO) {
            if (!access.owns(companyId)) return@withContext
            val current = dao.get(companyId, opportunityId) ?: OpportunityFlagEntity(companyId, opportunityId, null, null)
            val next = transform(current)
            if (next != current) dao.upsert(next)
        }

    private companion object {
        const val MAX_SQL_ARGS = 500
    }
}
