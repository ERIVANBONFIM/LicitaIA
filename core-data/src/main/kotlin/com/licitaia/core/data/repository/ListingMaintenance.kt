package com.licitaia.core.data.repository

import com.licitaia.connector.api.ConnectorRegistry
import com.licitaia.connector.api.WithdrawnListingSource
import com.licitaia.core.data.db.ComprasGovCacheDao
import com.licitaia.core.data.db.LicitaDatabase
import com.licitaia.core.data.db.OpportunityDao
import com.licitaia.core.data.db.RelevanceScoreDao
import com.licitaia.core.data.db.TenderDao
import com.licitaia.core.data.db.toDomain
import com.licitaia.core.data.edital.EditalStore
import com.licitaia.domain.model.Opportunity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fonte extra de oportunidades que a limpeza NUNCA apaga (além das licitações salvas em `tenders`, que já cobrem
 * interesse/participação, análise, proposta e sessão/robô vinculados). Contribuições via `@IntoSet` no Hilt.
 */
fun interface ProtectedOpportunitySource {
    suspend fun protectedOpportunityIds(): Set<String>
}

/**
 * Regra da limpeza do cache local de licitações (lógica pura, testável). Apaga:
 * - retiradas: canceladas/revogadas/anuladas/desertas/fracassadas/suspensas vistas pelas fontes;
 * - encerradas: prazo de propostas conhecido E sessão (quando informada) já passaram;
 * - antigas: publicadas antes do 1º dia do mês atual que não estão mais abertas (prazo vencido ou nunca confirmado).
 * NUNCA apaga as protegidas (por id ou pelo número de controle PNCP equivalente, ver [OpportunityDeduplicator.keyOfId]).
 */
internal object ListingCleanupRule {
    enum class Reason { WITHDRAWN, ENDED, OLD }

    fun reason(o: Opportunity, now: Long, monthStart: Long, withdrawn: Set<String>, protectedKeys: Set<String>): Reason? {
        if (o.id in protectedKeys || OpportunityDeduplicator.key(o) in protectedKeys) return null
        if (o.id in withdrawn) return Reason.WITHDRAWN
        if (isEnded(o, now)) return Reason.ENDED
        if (o.publishedAt < monthStart && !isOpen(o, now)) return Reason.OLD
        return null
    }

    /** Prazo de propostas conhecido e vencido, e a sessão (se informada) também já passou. */
    fun isEnded(o: Opportunity, now: Long): Boolean =
        o.hasProposalDeadline && o.proposalDeadline < now && (o.sessionAt <= Opportunity.DEADLINE_UNKNOWN || o.sessionAt < now)

    /** Aberta ou vai abrir: prazo de propostas conhecido e ainda no futuro. */
    fun isOpen(o: Opportunity, now: Long): Boolean = o.hasProposalDeadline && o.proposalDeadline >= now

    /** Ids e chaves de dedup das oportunidades protegidas. */
    fun protectedKeys(ids: Collection<String>): Set<String> = ids.flatMapTo(HashSet()) { listOf(it, OpportunityDeduplicator.keyOfId(it)) }

    /** Arquivo de edital `{tenderId}.pdf|.txt|.anexo.pdf|.pdf.part` órfão (licitação apagada) e antigo o bastante. */
    fun isOrphanEdital(name: String, lastModified: Long, tenderIds: Set<Long>, now: Long, minAgeMs: Long = 60L * 60 * 1000): Boolean {
        val id = name.substringBefore('.').toLongOrNull() ?: return false
        return id !in tenderIds && now - lastModified >= minAgeMs
    }
}

/**
 * Limpeza do cache local de licitações (rodada no fim da atualização diária e, se ainda não rodou no dia, ao abrir o
 * app): aplica [ListingCleanupRule] ao cache de oportunidades, apaga as linhas encerradas/retiradas do cache do
 * Compras.gov.br, as notas por IA das apagadas e os PDFs/textos de edital de licitações que não existem mais. VACUUM só
 * quando pedido (no máximo 1x por semana, decidido por quem chama). Só DELETE/SELECT em tabelas existentes.
 */
@Singleton
class ListingMaintenance @Inject constructor(
    private val registry: ConnectorRegistry,
    private val opportunityDao: OpportunityDao,
    private val comprasGovDao: ComprasGovCacheDao,
    private val tenderDao: TenderDao,
    private val relevanceDao: RelevanceScoreDao,
    private val editalStore: EditalStore,
    private val database: LicitaDatabase,
    private val protectedSources: Set<@JvmSuppressWildcards ProtectedOpportunitySource>,
) {
    data class Result(
        val opportunities: Int,
        val withdrawn: Int,
        val listingRows: Int,
        val editalFiles: Int,
        val vacuumed: Boolean,
    )

    private val mutex = Mutex()

    /** Ids hoje no cache de oportunidades (para contar as novas da atualização diária). */
    suspend fun cachedIds(): Set<String> = withContext(Dispatchers.IO) { opportunityDao.ids().toHashSet() }

    suspend fun cleanup(now: Long, monthStart: Long, vacuum: Boolean): Result = mutex.withLock {
        withContext(Dispatchers.IO) {
            val withdrawn = runCatching { registry.all() }.getOrDefault(emptyList())
                .filterIsInstance<WithdrawnListingSource>()
                .flatMapTo(HashSet()) { it.drainWithdrawnIds() }
            val protectedIds = tenderDao.allOpportunityIds() +
                protectedSources.flatMap { s -> runCatching { s.protectedOpportunityIds() }.getOrDefault(emptySet()) }
            val keys = ListingCleanupRule.protectedKeys(protectedIds)

            // Cache de oportunidades (o que a busca/radares mostram ao abrir).
            val doomed = ArrayList<String>()
            var withdrawnCount = 0
            for (entity in opportunityDao.getAll()) {
                val reason = ListingCleanupRule.reason(entity.toDomain(), now, monthStart, withdrawn, keys) ?: continue
                if (reason == ListingCleanupRule.Reason.WITHDRAWN) withdrawnCount++
                doomed += entity.id
            }
            var removed = 0
            doomed.chunked(MAX_SQL_ARGS).forEach { chunk ->
                removed += opportunityDao.deleteUnprotected(chunk)
                runCatching { relevanceDao.deleteForOpportunities(chunk) }
            }

            // Cache de linhas do Compras.gov.br: encerradas e retiradas (as de prazo desconhecido ficam: são as candidatas
            // do enriquecimento de prazo no PNCP).
            var rows = runCatching { comprasGovDao.deleteEnded(now) }.getOrDefault(0)
            withdrawn.filter { it !in keys && OpportunityDeduplicator.keyOfId(it) !in keys }.chunked(MAX_SQL_ARGS).forEach { chunk ->
                rows += runCatching { comprasGovDao.deleteUnprotected(chunk) }.getOrDefault(0)
            }

            val files = runCatching { deleteOrphanEditais(now) }.getOrDefault(0)
            val vacuumed = vacuum && runCatching { database.openHelper.writableDatabase.execSQL("VACUUM") }.isSuccess
            Result(removed, withdrawnCount, rows, files, vacuumed)
        }
    }

    /** PDFs/textos de edital em `filesDir/editais/{empresa}/` de licitações que não existem mais. */
    private suspend fun deleteOrphanEditais(now: Long): Int {
        val root = editalStore.rootDirectory()
        if (!root.isDirectory) return 0
        val tenderIds = tenderDao.allIds().toHashSet()
        var deleted = 0
        root.listFiles()?.filter(File::isDirectory)?.forEach { companyDir ->
            companyDir.listFiles()?.forEach { f ->
                if (f.isFile && ListingCleanupRule.isOrphanEdital(f.name, f.lastModified(), tenderIds, now) && f.delete()) deleted++
            }
        }
        return deleted
    }

    private companion object {
        const val MAX_SQL_ARGS = 500
    }
}
