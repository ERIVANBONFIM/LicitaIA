package com.licitaia.core.data.repository

import com.licitaia.core.data.db.AuditDao
import com.licitaia.core.data.db.toDomain
import com.licitaia.core.data.db.toEntity
import com.licitaia.core.data.session.SessionHolder
import com.licitaia.domain.model.AuditAction
import com.licitaia.domain.model.AuditEvent
import com.licitaia.domain.model.AuditOrigin
import com.licitaia.domain.model.AuditResult
import com.licitaia.domain.model.Portal
import com.licitaia.domain.repository.AuditRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuditRepositoryImpl @Inject constructor(
    private val auditDao: AuditDao,
    private val sessionHolder: SessionHolder,
) : AuditRepository {

    override fun observeEvents(companyId: Long?): Flow<List<AuditEvent>> =
        combine(auditDao.observeAll(), sessionHolder.state) { list, session ->
            list.filter { session != null && !session.user.demo && it.companyId in session.user.companyIds && (companyId == null || it.companyId == companyId) }.map { it.toDomain() }
        }
    override suspend fun record(
        action: AuditAction,
        result: AuditResult,
        origin: AuditOrigin,
        portal: Portal?,
        tenderNumber: String?,
        item: String?,
        previousValue: String?,
        newValue: String?,
        reason: String?,
        details: String,
    ) {
        val session = sessionHolder.current
        recordAs(
            user = session?.user?.name ?: SYSTEM_USER,
            companyId = session?.activeCompany?.id,
            companyName = session?.activeCompany?.tradeName ?: "—",
            action = action, result = result, origin = origin, portal = portal, tenderNumber = tenderNumber,
            item = item, previousValue = previousValue, newValue = newValue, reason = reason, details = details,
        )
    }

    /** Variante com usuário/empresa explícitos (login falho, troca de empresa, robô). */
    suspend fun recordAs(
        user: String,
        companyId: Long?,
        companyName: String,
        action: AuditAction,
        result: AuditResult = AuditResult.SUCESSO,
        origin: AuditOrigin = AuditOrigin.USUARIO,
        portal: Portal? = null,
        tenderNumber: String? = null,
        item: String? = null,
        previousValue: String? = null,
        newValue: String? = null,
        reason: String? = null,
        details: String = "",
    ) {
        val event = AuditEvent(
            timestamp = System.currentTimeMillis(),
            user = user,
            companyId = companyId,
            companyName = companyName,
            portal = portal?.displayName,
            tenderNumber = tenderNumber,
            item = item,
            action = action,
            previousValue = previousValue,
            newValue = newValue,
            reason = reason,
            origin = origin,
            result = result,
            details = details,
        )
        runCatching { auditDao.insert(event.toEntity()) }
    }

    private companion object {
        const val SYSTEM_USER = "Sistema"
    }
}

