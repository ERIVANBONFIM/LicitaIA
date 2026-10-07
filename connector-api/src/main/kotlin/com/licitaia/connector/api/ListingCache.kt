package com.licitaia.connector.api

import com.licitaia.domain.model.Opportunity
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Marca de sincronização de uma partição (modalidade + UF; UF vazia = país inteiro) do cache persistente de linhas.
 * [lastFullSyncAt] = última varredura completa da janela (0 = nunca; só houve leituras incrementais);
 * [lastSyncAt] = última leitura bem-sucedida (completa ou incremental).
 */
data class ListingSyncMark(
    val modalityCode: Int,
    val uf: String,
    val lastFullSyncAt: Long,
    val lastSyncAt: Long,
    /** A última varredura completa atingiu o teto de linhas (as publicações mais recentes foram priorizadas). */
    val truncated: Boolean = false,
)

/** Consulta ao cache: modalidade, UFs (vazio = todas), publicadas desde [publishedSince] e não encerradas em [openAt]. */
data class ListingRowQuery(
    val modalityCode: Int,
    val ufs: Set<String>,
    val publishedSince: Long,
    val openAt: Long,
)

/**
 * Cache persistente das linhas (já convertidas em [Opportunity]) de uma fonte de leitura completa (Compras.gov.br).
 * Implementação real em Room (core-data); [InMemoryListingRowStore] para testes e uso sem banco.
 * A leitura é feita em blocos ([page], keyset por id) para nunca manter a janela inteira em memória.
 */
interface ListingRowStore {
    suspend fun syncMark(modalityCode: Int, uf: String): ListingSyncMark?
    suspend fun saveSyncMark(mark: ListingSyncMark)
    suspend fun upsert(modalityCode: Int, rows: List<Opportunity>, fetchedAt: Long)
    suspend fun delete(ids: Collection<String>)

    /** Remove o que saiu da janela (publicado antes de [publishedBefore]) ou encerrou (prazo conhecido < [now]). */
    suspend fun prune(publishedBefore: Long, now: Long): Int

    /** Próximo bloco (ordenado por id, ids > [afterId]) que atende [query]. */
    suspend fun page(query: ListingRowQuery, afterId: String, limit: Int): List<Opportunity>
}

/** Implementação em memória (testes JVM e fallback sem banco). */
class InMemoryListingRowStore : ListingRowStore {
    private class Row(val code: Int, val opportunity: Opportunity)

    private val rows = java.util.concurrent.ConcurrentSkipListMap<String, Row>()
    private val marks = ConcurrentHashMap<String, ListingSyncMark>()

    val size: Int get() = rows.size

    override suspend fun syncMark(modalityCode: Int, uf: String): ListingSyncMark? = marks["$modalityCode|$uf"]

    override suspend fun saveSyncMark(mark: ListingSyncMark) {
        marks["${mark.modalityCode}|${mark.uf}"] = mark
    }

    override suspend fun upsert(modalityCode: Int, rows: List<Opportunity>, fetchedAt: Long) {
        rows.forEach { this.rows[it.id] = Row(modalityCode, it) }
    }

    override suspend fun delete(ids: Collection<String>) {
        ids.forEach { rows.remove(it) }
    }

    override suspend fun prune(publishedBefore: Long, now: Long): Int {
        val gone = rows.entries.filter { (_, r) -> r.opportunity.publishedAt < publishedBefore || r.opportunity.isProposalClosed(now) }
        gone.forEach { rows.remove(it.key) }
        return gone.size
    }

    override suspend fun page(query: ListingRowQuery, afterId: String, limit: Int): List<Opportunity> =
        rows.tailMap(afterId, false).values.asSequence()
            .filter { r ->
                val o = r.opportunity
                r.code == query.modalityCode && (query.ufs.isEmpty() || o.uf in query.ufs) &&
                    o.publishedAt >= query.publishedSince && !o.isProposalClosed(query.openAt)
            }
            .take(limit)
            .map { it.opportunity }
            .toList()
}

/** Sincronização em andamento de uma fonte de leitura completa: [rows] linhas baixadas até agora; [full] = varredura completa. */
data class ListingSyncProgress(val rows: Int, val full: Boolean)

/** Fonte que expõe o andamento da sincronização (null = nenhuma em andamento). */
interface ListingSyncProgressSource {
    val syncProgress: kotlinx.coroutines.flow.StateFlow<ListingSyncProgress?>
}

/**
 * Coordenação do tráfego ao PNCP numa mesma busca: a listagem do PNCP e as consultas de prazo do Compras.gov.br usam o
 * mesmo host (pncp.gov.br), que devolve 429 após poucas requisições seguidas. O repositório coloca este elemento no
 * contexto das fontes com triagem; elas esperam [listingDone] (com limite) antes de consultar prazos no PNCP, para que
 * as duas leituras não disputem o limite e a listagem do PNCP não volte parcial.
 */
class PncpTrafficGate : AbstractCoroutineContextElement(Key) {
    val listingDone: kotlinx.coroutines.CompletableDeferred<Unit> = kotlinx.coroutines.CompletableDeferred()

    companion object Key : CoroutineContext.Key<PncpTrafficGate>
}

/**
 * Política de sincronização das fontes de leitura completa para a coroutine atual. O Worker de alertas roda com
 * [INCREMENTAL_ONLY]: nunca dispara a varredura completa da janela (só as publicações desde a última sincronização).
 * A atualização diária (05:30) roda com [DAILY_FULL]: força a varredura completa (salvo se outra terminou há pouco) e
 * espera ela terminar, sem o limite de espera da tela.
 * Uso: `withContext(SourceSyncPolicy.INCREMENTAL_ONLY) { repository.runRadar(...) }`.
 */
class SourceSyncPolicy private constructor(
    val incrementalOnly: Boolean,
    val forceFull: Boolean = false,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SourceSyncPolicy> {
        val DEFAULT = SourceSyncPolicy(incrementalOnly = false)
        val INCREMENTAL_ONLY = SourceSyncPolicy(incrementalOnly = true)
        val DAILY_FULL = SourceSyncPolicy(incrementalOnly = false, forceFull = true)
    }
}

/**
 * Situações que tiram a contratação do ar (lógica pura, compartilhada pelos conectores): cancelada, revogada, anulada,
 * deserta, fracassada ou suspensa. `situacaoCompraId` do PNCP: 2 Revogada, 3 Anulada, 4 Suspensa (1 = Divulgada).
 */
object WithdrawnSituation {
    val WORDS = listOf("revogad", "anulad", "suspens", "cancelad", "desert", "fracassad")
    val PNCP_IDS = setOf(2, 3, 4)

    fun isWithdrawn(situacaoId: Int?, situacaoNome: String?): Boolean {
        if (situacaoId != null && situacaoId in PNCP_IDS) return true
        val n = situacaoNome?.lowercase() ?: return false
        return WORDS.any { n.contains(it) }
    }
}

/**
 * Fonte que registra as contratações que viu como retiradas ([WithdrawnSituation]); a limpeza diária apaga essas do
 * cache local de oportunidades (as linhas do próprio conector ele mesmo já descarta).
 */
interface WithdrawnListingSource {
    /** Ids de oportunidade vistos como retirados desde a última chamada (e esvazia o registro). */
    fun drainWithdrawnIds(): Set<String>
}

/** Registro em memória dos ids retirados (com teto, para nunca crescer sem limite). */
class WithdrawnIds(private val max: Int = 20_000) {
    private val ids = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    fun record(id: String) {
        if (ids.size < max) ids += id
    }

    fun drain(): Set<String> {
        val out = HashSet(ids)
        ids.removeAll(out)
        return out
    }
}
