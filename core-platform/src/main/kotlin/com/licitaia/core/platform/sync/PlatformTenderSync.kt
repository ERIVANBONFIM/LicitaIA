package com.licitaia.core.platform.sync

import com.licitaia.core.platform.PlatformConfig
import com.licitaia.core.platform.db.PlatformTenderDao
import com.licitaia.core.platform.db.PlatformTenderEntity
import com.licitaia.core.platform.net.TenderDto
import com.licitaia.core.platform.net.TenderPageDto

data class SyncResult(val fetched: Int, val upserted: Int, val totalLocal: Int, val pages: Int)

/**
 * Sincronização de LEITURA das licitações para o espelho local.
 *
 * Enquanto o endpoint incremental (`GET /sync?since=` — CONTRATO L1) não existe, faz pull paginado por
 * `GET /licitacoes?leve=true&ordenar=updatedAt` (desc) e faz upsert por `id`. Como vem ordenado por
 * `updatedAt` desc, para na primeira página cujo registro já é conhecido (incremental aproximado).
 *
 * Recebe `fetchPage` como função → testável na JVM sem rede; o DAO é uma interface → fake nos testes.
 */
class PlatformTenderSync(
    private val dao: PlatformTenderDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun sync(fetchPage: suspend (page: Int) -> TenderPageDto): SyncResult {
        val known = dao.latestUpdatedAt()
        var page = 1
        var fetched = 0
        var upserted = 0
        var pagesRead = 0
        while (page <= PlatformConfig.SYNC_MAX_PAGES) {
            val pageData = fetchPage(page)
            pagesRead++
            if (pageData.data.isEmpty()) break
            fetched += pageData.data.size
            val now = clock()
            dao.upsert(pageData.data.map { it.toEntity(now) })
            upserted += pageData.data.size
            // Já alcançamos o que o espelho conhece: o resto da lista desc é mais antigo → pode parar.
            if (known != null && pageData.data.any { (it.updatedAt ?: "") <= known }) break
            if (page >= pageData.totalPages) break
            page++
        }
        return SyncResult(fetched = fetched, upserted = upserted, totalLocal = dao.count(), pages = pagesRead)
    }
}

/** Mapeia o DTO de fio para o espelho local. */
fun TenderDto.toEntity(syncedAt: Long): PlatformTenderEntity = PlatformTenderEntity(
    id = id,
    numero = numero,
    orgao = orgao,
    objeto = objeto,
    modalidade = modalidade,
    valorEstimado = valorEstimado,
    dataAbertura = dataAbertura,
    dataEncerramento = dataEncerramento,
    portal = portal,
    portalUrl = portalUrl,
    estado = estado,
    cidade = cidade,
    fase = fase,
    status = status,
    favorita = favorita,
    scoreRelevancia = scoreRelevancia,
    updatedAt = updatedAt,
    empresaId = empresaId,
    syncedAt = syncedAt,
)
