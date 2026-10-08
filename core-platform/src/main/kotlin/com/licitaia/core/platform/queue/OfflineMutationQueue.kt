package com.licitaia.core.platform.queue

import com.licitaia.core.platform.db.PlatformMutationDao
import com.licitaia.core.platform.db.PlatformMutationEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Ações offline suportadas (estrutura pronta; o drenador real amadurece com o F3/L6). */
enum class MutationType { FAVORITAR, OCULTAR, ARQUIVAR, FASE }

/**
 * Fila FIFO de mutações feitas offline. Grava a intenção localmente e a reenvia em ordem quando houver rede.
 * Reaplicar é seguro: as rotas do contrato são idempotentes por estado final.
 *
 * Depende apenas da interface do DAO → testável na JVM com um fake, sem Room. Fornecida pelo PlatformModule.
 */
class OfflineMutationQueue(
    private val dao: PlatformMutationDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun enqueue(type: MutationType, targetId: String, payload: String? = null): Long =
        dao.enqueue(
            PlatformMutationEntity(type = type.name, targetId = targetId, payload = payload, createdAt = clock()),
        )

    suspend fun pending(): List<PlatformMutationEntity> = dao.pending()

    fun observeCount(): Flow<Int> = dao.observePending().map { it.size }

    suspend fun count(): Int = dao.count()

    /** Marca o envio como concluído (remove da fila). */
    suspend fun markSent(mutation: PlatformMutationEntity) = dao.removeById(mutation.id)

    /** Registra uma falha recuperável (incrementa tentativas; permanece na fila para novo backoff). */
    suspend fun markFailed(mutation: PlatformMutationEntity, error: String?) = dao.markFailed(mutation.id, error)

    /** 404/400 definitivo: descarta a mutação (contrato §4.4: em 404 descartar). */
    suspend fun discard(mutation: PlatformMutationEntity) = dao.removeById(mutation.id)

    suspend fun clear() = dao.clear()
}
