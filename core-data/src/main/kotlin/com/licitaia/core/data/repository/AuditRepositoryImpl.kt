package com.licitaia.core.data.repository

import android.util.Log
import androidx.room.withTransaction
import com.licitaia.core.data.db.AuditDao
import com.licitaia.core.data.db.LicitaDatabase
import com.licitaia.core.data.db.toDomain
import com.licitaia.core.data.db.toEntity
import com.licitaia.core.data.repository.RepositoryAccess.Companion.sameRealm
import com.licitaia.core.data.session.SessionHolder
import com.licitaia.core.security.AuditHashChain
import com.licitaia.domain.model.AuditAction
import com.licitaia.domain.model.AuditEvent
import com.licitaia.domain.model.AuditOrigin
import com.licitaia.domain.model.AuditResult
import com.licitaia.domain.model.IntegrityReport
import com.licitaia.domain.model.Portal
import com.licitaia.domain.repository.AuditRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Trilha de auditoria append-only com hash encadeado: cada evento guarda o hash do anterior e o próprio
 * SHA-256 (`prevHash` + campos essenciais). A inserção lê a cabeça da cadeia e grava dentro de uma única
 * transação, serializada por [Mutex], para que dois registros concorrentes não apontem para o mesmo anterior.
 */
@Singleton
class AuditRepositoryImpl @Inject constructor(
    private val db: LicitaDatabase,
    private val auditDao: AuditDao,
    private val sessionHolder: SessionHolder,
) : AuditRepository {

    private val chainLock = Mutex()

    /**
     * Só eventos das empresas do usuário. Como o usuário demo pertence apenas à empresa demo (e usuários
     * reais nunca a ela), os eventos da demonstração ficam confinados ao espaço demo.
     */
    override fun observeEvents(companyId: Long?): Flow<List<AuditEvent>> =
        combine(auditDao.observeAll(), sessionHolder.state) { list, session ->
            list.filter { session != null && session.sameRealm() && it.companyId in session.user.companyIds && (companyId == null || it.companyId == companyId) }.map { it.toDomain() }
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
        try {
            chainLock.withLock {
                db.withTransaction {
                    val head = auditDao.last()?.hash
                    auditDao.insert(AuditHashChain.chain(head, event).toEntity())
                }
            }
        } catch (e: Exception) {
            // Nunca derruba a operação de negócio, mas também não some em silêncio. Sem dados do evento no log.
            Log.w(TAG, "Falha ao gravar evento de auditoria (${action.name}/${result.name}): ${e.javaClass.simpleName}")
        }
    }

    override suspend fun verifyIntegrity(): IntegrityReport = withContext(Dispatchers.IO) {
        // Percorre a tabela em páginas, em ordem de inserção, mantendo só o evento anterior em memória.
        val total = auditDao.count()
        var unhashed = 0
        var verified = 0
        var previous: AuditEvent? = null
        var afterId = 0L
        while (true) {
            val page = auditDao.pageAfter(afterId, PAGE)
            if (page.isEmpty()) break
            for (entity in page) {
                val event = entity.toDomain()
                afterId = event.id
                if (previous == null && event.hash.isEmpty()) { unhashed++; continue }
                val linkOk = previous == null || event.prevHash == previous.hash
                val selfOk = event.hash.isNotEmpty() && event.hash == AuditHashChain.hashOf(event.prevHash, event)
                if (!linkOk || !selfOk) {
                    return@withContext IntegrityReport(total = total, verified = verified, firstBroken = event.id, unhashed = unhashed)
                }
                verified++
                previous = event
            }
            if (page.size < PAGE) break
        }
        IntegrityReport(total = total, verified = verified, firstBroken = null, unhashed = unhashed)
    }

    private companion object {
        const val SYSTEM_USER = "Sistema"
        const val TAG = "LicitaIA.Audit"
        const val PAGE = 500
    }
}
