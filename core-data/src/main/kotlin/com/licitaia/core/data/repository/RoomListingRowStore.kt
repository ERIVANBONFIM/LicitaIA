package com.licitaia.core.data.repository

import com.licitaia.connector.api.ListingRowQuery
import com.licitaia.connector.api.ListingRowStore
import com.licitaia.connector.api.ListingSyncMark
import com.licitaia.core.data.db.ComprasGovCacheDao
import com.licitaia.core.data.db.ComprasGovRowEntity
import com.licitaia.core.data.db.ComprasGovSyncEntity
import com.licitaia.domain.model.Opportunity
import com.licitaia.domain.model.Portal
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cache persistente (Room, tabelas `comprasgov_rows`/`comprasgov_sync`, versão 10) das linhas do Compras.gov.br.
 * A leitura filtra modalidade/UF/janela/prazo no SQL e devolve blocos pequenos: a janela inteira nunca fica em memória.
 */
@Singleton
class RoomListingRowStore @Inject constructor(private val dao: ComprasGovCacheDao) : ListingRowStore {

    override suspend fun syncMark(modalityCode: Int, uf: String): ListingSyncMark? =
        dao.syncMark(modalityCode, uf)?.let { ListingSyncMark(it.modalityCode, it.uf, it.lastFullSyncAt, it.lastSyncAt, it.truncated) }

    override suspend fun saveSyncMark(mark: ListingSyncMark) =
        dao.saveSyncMark(ComprasGovSyncEntity(mark.modalityCode, mark.uf, mark.lastFullSyncAt, mark.lastSyncAt, mark.truncated))

    override suspend fun upsert(modalityCode: Int, rows: List<Opportunity>, fetchedAt: Long) {
        if (rows.isNotEmpty()) dao.upsert(rows.map { it.toRow(modalityCode, fetchedAt) })
    }

    override suspend fun delete(ids: Collection<String>) {
        ids.chunked(MAX_SQL_ARGS).forEach { dao.delete(it) }
    }

    override suspend fun prune(publishedBefore: Long, now: Long): Int = dao.prune(publishedBefore, now)

    override suspend fun page(query: ListingRowQuery, afterId: String, limit: Int): List<Opportunity> {
        val ufs = query.ufs.toList()
        // Muitas UFs: filtro só no aparelho (evita estourar o limite de argumentos do SQLite).
        val anyUf = ufs.isEmpty() || ufs.size > MAX_SQL_ARGS
        return dao.page(query.modalityCode, anyUf, if (anyUf) emptyList() else ufs, query.publishedSince, query.openAt, afterId, limit)
            .map { it.toDomain() }
    }

    private companion object {
        const val MAX_SQL_ARGS = 500
    }
}

internal fun Opportunity.toRow(modalityCode: Int, fetchedAt: Long) = ComprasGovRowEntity(
    id = id, modalityCode = modalityCode, uf = uf, publishedAt = publishedAt, proposalDeadline = proposalDeadline,
    sessionAt = sessionAt, number = number, agency = agency, objectDescription = objectDescription, modality = modality,
    segment = segment, city = city, estimatedValue = estimatedValue, keywords = keywords, editalUrl = editalUrl,
    noDispute = noDispute, fetchedAt = fetchedAt, proposalOpening = proposalOpening,
)

internal fun ComprasGovRowEntity.toDomain() = Opportunity(
    id = id, portal = Portal.COMPRAS_GOV, number = number, agency = agency, objectDescription = objectDescription,
    modality = modality, segment = segment, uf = uf, city = city, estimatedValue = estimatedValue, publishedAt = publishedAt,
    proposalDeadline = proposalDeadline, sessionAt = sessionAt, keywords = keywords, editalUrl = editalUrl, noDispute = noDispute,
    proposalOpening = proposalOpening,
)
