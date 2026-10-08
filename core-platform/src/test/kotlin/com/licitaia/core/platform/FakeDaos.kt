package com.licitaia.core.platform

import com.licitaia.core.platform.db.PlatformMutationDao
import com.licitaia.core.platform.db.PlatformMutationEntity
import com.licitaia.core.platform.db.PlatformTenderDao
import com.licitaia.core.platform.db.PlatformTenderEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** DAO de licitações em memória para testes JVM (sem Room). */
class FakeTenderDao : PlatformTenderDao {
    val rows = linkedMapOf<String, PlatformTenderEntity>()
    private val flow = MutableStateFlow<List<PlatformTenderEntity>>(emptyList())

    override suspend fun upsert(tenders: List<PlatformTenderEntity>) {
        tenders.forEach { rows[it.id] = it }
        flow.value = rows.values.toList()
    }

    override fun observeAll(): Flow<List<PlatformTenderEntity>> = flow
    override suspend fun all(): List<PlatformTenderEntity> = rows.values.toList()
    override suspend fun byId(id: String): PlatformTenderEntity? = rows[id]
    override suspend fun latestUpdatedAt(): String? = rows.values.mapNotNull { it.updatedAt }.maxOrNull()
    override suspend fun count(): Int = rows.size
    override suspend fun clear() { rows.clear(); flow.value = emptyList() }
}

/** DAO de fila em memória para testes JVM. */
class FakeMutationDao : PlatformMutationDao {
    private var seq = 0L
    val rows = mutableListOf<PlatformMutationEntity>()
    private val flow = MutableStateFlow<List<PlatformMutationEntity>>(emptyList())

    override suspend fun enqueue(mutation: PlatformMutationEntity): Long {
        val id = ++seq
        rows.add(mutation.copy(id = id))
        emit()
        return id
    }

    override suspend fun pending(): List<PlatformMutationEntity> = rows.sortedWith(compareBy({ it.createdAt }, { it.id }))
    override fun observePending(): Flow<List<PlatformMutationEntity>> = flow
    override suspend fun markFailed(id: Long, error: String?) {
        rows.replaceAll { if (it.id == id) it.copy(attempts = it.attempts + 1, lastError = error) else it }
        emit()
    }
    override suspend fun remove(mutation: PlatformMutationEntity) { rows.removeAll { it.id == mutation.id }; emit() }
    override suspend fun removeById(id: Long) { rows.removeAll { it.id == id }; emit() }
    override suspend fun count(): Int = rows.size
    override suspend fun clear() { rows.clear(); emit() }

    private fun emit() { flow.value = pendingSorted() }
    private fun pendingSorted() = rows.sortedWith(compareBy({ it.createdAt }, { it.id }))
}
