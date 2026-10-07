package com.licitaia.core.data.competition

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.licitaia.core.data.db.CompetitionDao
import com.licitaia.core.data.db.toDomain
import com.licitaia.core.data.db.toEntity
import com.licitaia.core.data.repository.RepositoryAccess
import com.licitaia.core.data.session.SessionHolder
import com.licitaia.domain.competition.CompetitionResultsSync
import com.licitaia.domain.competition.CompetitionSyncReport
import com.licitaia.domain.competition.CompetitorRanking
import com.licitaia.domain.competition.MarketQuery
import com.licitaia.domain.competition.MarketSnapshot
import com.licitaia.domain.competition.PublicAwardResult
import com.licitaia.domain.competition.PublicResultsSource
import com.licitaia.domain.competition.TrackedTender
import com.licitaia.domain.competition.TrackedTenderProvider
import com.licitaia.domain.model.AuditAction
import com.licitaia.domain.model.AuditOrigin
import com.licitaia.domain.model.CompetitionRecord
import com.licitaia.domain.model.Radar
import com.licitaia.domain.model.Segment
import com.licitaia.domain.repository.AuditRepository
import com.licitaia.domain.repository.RadarRepository
import com.licitaia.domain.scoring.SegmentAffinity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Busca automática dos resultados públicos (PNCP) das licitações da empresa e da base "Concorrentes do meu segmento".
 *
 * - Resultados das licitações em que a empresa PARTICIPOU (ou venceu pelo CNPJ) viram [CompetitionRecord] no histórico de
 *   concorrência (tabela existente), com o vencedor/CNPJ no resumo e a fonte "PNCP <controle>" (deduplicação).
 * - Todos os resultados (da empresa e do segmento) ficam no DataStore como base do ranking de concorrentes.
 * - Licitações já resolvidas não são consultadas de novo; as sem resultado são reconsultadas na próxima execução.
 */
@Singleton
class CompetitionResultsSyncImpl @Inject constructor(
    private val source: PublicResultsSource,
    private val providers: Set<@JvmSuppressWildcards TrackedTenderProvider>,
    private val competitionDao: CompetitionDao,
    private val radars: RadarRepository,
    private val access: RepositoryAccess,
    private val holder: SessionHolder,
    private val audit: AuditRepository,
    private val prefs: DataStore<Preferences>,
) : CompetitionResultsSync {

    private val mutex = Mutex()
    private val _running = MutableStateFlow(false)
    override val running: StateFlow<Boolean> = _running.asStateFlow()

    override fun observeMarket(companyId: Long): Flow<MarketSnapshot> =
        prefs.data.map { p ->
            if (!access.owns(companyId)) MarketSnapshot()
            else MarketSnapshot(
                results = MarketStore.decode(p[marketKey(companyId)]),
                updatedAt = p[lastRunKey(companyId)],
                keywords = p[keywordsKey(companyId)]?.split('|')?.filter { it.isNotBlank() }.orEmpty(),
            )
        }.distinctUntilChanged()

    override fun observeLastReport(companyId: Long): Flow<CompetitionSyncReport?> =
        prefs.data.map { p -> if (!access.owns(companyId)) null else MarketStore.decodeReport(p[reportKey(companyId)]) }.distinctUntilChanged()

    override suspend fun refreshIfDue(companyId: Long, minIntervalMs: Long): Result<CompetitionSyncReport>? {
        val last = prefs.data.first()[lastRunKey(companyId)] ?: 0L
        if (System.currentTimeMillis() - last in 0 until minIntervalMs) return null
        return refresh(companyId, includeMarket = true)
    }

    override suspend fun refresh(companyId: Long, includeMarket: Boolean): Result<CompetitionSyncReport> {
        if (!mutex.tryLock()) return Result.failure(IllegalStateException("A atualização de resultados já está em andamento."))
        _running.value = true
        return try {
            withContext(Dispatchers.IO) { runCatching { doRefresh(companyId, includeMarket) } }
                .onFailure { e -> if (e is CancellationException) throw e }
                .also { result ->
                    // Falha também fica registrada (com horário) para a tela explicar o que houve.
                    result.exceptionOrNull()?.let { e ->
                        if (access.owns(companyId)) saveReport(
                            companyId,
                            CompetitionSyncReport(System.currentTimeMillis(), 0, 0, 0, 0, error = e.message ?: "Falha ao consultar o PNCP."),
                        )
                    }
                }
        } finally {
            _running.value = false
            mutex.unlock()
        }
    }

    private suspend fun doRefresh(companyId: Long, includeMarket: Boolean): CompetitionSyncReport {
        access.requireCompany(companyId)
        val session = holder.current ?: error("Sessão encerrada.")
        val ourCnpj = CompetitorRanking.digits(session.activeCompany.cnpj)
        val now = System.currentTimeMillis()
        val stored = prefs.data.first()
        val resolved = stored[resolvedKey(companyId)].orEmpty()

        val tracked = collectTracked(companyId)
        val candidates = tracked
            .filter { it.controlNumber !in resolved }
            // Sessão já ocorrida (ou data desconhecida) e no máximo 1 ano atrás.
            .filter { it.sessionAt <= 0L || it.sessionAt in (now - YEAR_MS)..(now - SESSION_GRACE_MS) }
            .sortedByDescending { it.sessionAt }
            .take(MAX_TENDERS_PER_RUN)

        val existing = competitionDao.observeByCompany(companyId).first().map { it.toDomain() }.toMutableList()
        val ownAwards = mutableListOf<PublicAwardResult>()
        val newlyResolved = mutableSetOf<String>()
        var checked = 0
        var imported = 0
        var pending = 0
        var partial = false
        var firstError: Throwable? = null

        for (t in candidates) {
            if (holder.current?.activeCompany?.id != companyId) break
            val batch = try {
                source.awardResults(t.controlNumber, MAX_ITEMS_PER_TENDER)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (firstError == null) firstError = e
                // Sem rede/PNCP fora do ar: não adianta insistir nas demais.
                break
            }
            if (batch.partial && batch.results.isEmpty()) { partial = true; break }
            checked++
            if (batch.partial) partial = true
            if (batch.results.isEmpty()) { pending++; continue }
            val results = batch.results.map { r ->
                r.copy(
                    agency = r.agency.ifBlank { t.agency },
                    objectSummary = r.objectSummary.ifBlank { t.objectDescription },
                    ownTender = true,
                )
            }
            ownAwards += results
            if (!batch.partial) newlyResolved += t.controlNumber
            val record = ResultRecords.toRecord(companyId, t, results, ourCnpj, now) ?: continue
            if (ResultRecords.alreadyRecorded(existing, t)) continue
            access.requireCompany(companyId)
            val id = competitionDao.insert(record.toEntity())
            existing += record.copy(id = id)
            imported++
        }
        if (checked == 0 && firstError != null) throw firstError!!

        var marketCount = 0
        var keywords = emptyList<String>()
        val market = mutableListOf<PublicAwardResult>()
        if (includeMarket && !partial && holder.current?.activeCompany?.id == companyId) {
            val activeRadars = radars.observeRadars(companyId).first().filter { it.active }
            keywords = MarketKeywords.from(activeRadars, session.activeCompany.segment)
            val ufs = activeRadars.flatMap { it.ufs }.distinct().ifEmpty { listOfNotNull(session.activeCompany.uf.takeIf { it.length == 2 }) }
            if (keywords.isNotEmpty()) {
                try {
                    val batch = source.recentAwardsLike(MarketQuery(keywords = keywords, ufs = ufs.take(MAX_UFS)))
                    market += batch.results
                    marketCount = batch.results.size
                    if (batch.partial) partial = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (firstError == null) firstError = e
                }
            }
        }

        val report = CompetitionSyncReport(
            finishedAt = System.currentTimeMillis(), checked = checked, imported = imported, pending = pending,
            marketResults = marketCount, partial = partial,
        )
        prefs.edit { p ->
            val previous = MarketStore.decode(p[marketKey(companyId)])
            p[marketKey(companyId)] = MarketStore.encode(MarketStore.merge(previous, ownAwards + market))
            p[resolvedKey(companyId)] = (resolved + newlyResolved).toList().takeLast(MAX_RESOLVED).toSet()
            p[lastRunKey(companyId)] = report.finishedAt
            if (keywords.isNotEmpty()) p[keywordsKey(companyId)] = keywords.joinToString("|")
            p[reportKey(companyId)] = MarketStore.encodeReport(report)
        }
        if (imported > 0 || marketCount > 0) {
            audit.record(
                AuditAction.CADASTRO, origin = AuditOrigin.SISTEMA,
                newValue = "$imported resultado(s) da empresa · $marketCount do segmento",
                details = "Resultados públicos importados do PNCP para a Concorrência",
            )
        }
        return report
    }

    /** Une as fontes (licitações salvas, "minhas licitações"...) por número de controle. */
    private suspend fun collectTracked(companyId: Long): List<TrackedTender> {
        val all = providers.flatMap { provider ->
            try {
                provider.trackedTenders(companyId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        }
        return all.groupBy { it.controlNumber }.map { (_, list) ->
            val base = list.firstOrNull { it.tenderId != null } ?: list.first()
            base.copy(
                participated = list.any { it.participated },
                knownOutcome = list.firstNotNullOfOrNull { it.knownOutcome },
                ourFinalBid = list.firstNotNullOfOrNull { it.ourFinalBid },
                sessionAt = list.maxOf { it.sessionAt },
            )
        }
    }

    private suspend fun saveReport(companyId: Long, report: CompetitionSyncReport) {
        prefs.edit { it[reportKey(companyId)] = MarketStore.encodeReport(report) }
    }

    private fun marketKey(companyId: Long) = stringPreferencesKey("competition_market:$companyId")
    private fun reportKey(companyId: Long) = stringPreferencesKey("competition_report:$companyId")
    private fun keywordsKey(companyId: Long) = stringPreferencesKey("competition_keywords:$companyId")
    private fun lastRunKey(companyId: Long) = longPreferencesKey("competition_last_run:$companyId")
    private fun resolvedKey(companyId: Long) = stringSetPreferencesKey("competition_resolved:$companyId")

    companion object {
        const val MAX_TENDERS_PER_RUN = 12
        const val MAX_ITEMS_PER_TENDER = 20
        const val MAX_RESOLVED = 2000
        const val MAX_UFS = 3
        private const val YEAR_MS = 365L * 24 * 60 * 60 * 1000
        /** A sessão precisa ter passado há pelo menos 1 h para valer a consulta. */
        private const val SESSION_GRACE_MS = 60L * 60 * 1000
    }
}

/** Conversão resultado público → registro de concorrência da empresa. Pura e testável. */
object ResultRecords {
    const val SOURCE_TAG = CompetitionResultsSync.PUBLIC_RESULT_TAG

    /**
     * Só a empresa que PARTICIPOU (ou que aparece como vencedora pelo CNPJ) ganha um registro de vitória/derrota;
     * licitações apenas de interesse alimentam somente a base de mercado. null = não registrar.
     */
    fun toRecord(companyId: Long, t: TrackedTender, results: List<PublicAwardResult>, ourCnpj: String, now: Long): CompetitionRecord? {
        if (results.isEmpty()) return null
        val us = CompetitorRanking.digits(ourCnpj)
        val ours = results.filter { us.length >= 11 && CompetitorRanking.digits(it.supplierDocument) == us }
        if (!t.participated && ours.isEmpty()) return null
        val won = ours.isNotEmpty() || t.knownOutcome == true
        val winners = results.groupBy { CompetitorRanking.digits(it.supplierDocument).ifEmpty { it.supplierName.lowercase() } }
            .values.sortedByDescending { list -> list.sumOf { it.homologatedValue } }
        val names = winners.take(3).joinToString("; ") { list ->
            val r = list.first()
            val doc = CompetitorRanking.formatDocument(r.supplierDocument)
            if (doc.isEmpty()) r.supplierName.trim() else "${r.supplierName.trim()} (CNPJ $doc)"
        } + if (winners.size > 3) " e mais ${winners.size - 3}" else ""
        val closing = results.sumOf { it.homologatedValue }
        val estimated = results.sumOf { it.estimatedValue }.takeIf { it > 0.0 } ?: t.estimatedValue
        val ourBid = ours.sumOf { it.homologatedValue }.takeIf { it > 0.0 } ?: t.ourFinalBid ?: 0.0
        return CompetitionRecord(
            companyId = companyId,
            portal = t.portal,
            tenderNumber = t.number,
            agency = t.agency,
            segment = t.segment,
            objectSummary = t.objectDescription.take(300),
            date = results.maxOf { it.resultDate }.takeIf { it > 0L } ?: t.sessionAt.takeIf { it > 0L } ?: now,
            competitors = winners.size.coerceAtLeast(1),
            estimatedValue = estimated,
            closingValue = closing,
            ourFinalBid = ourBid,
            won = won,
            // Custo não é publicado: margem desconhecida (0) — a tela ignora nos indicadores de margem.
            ourMarginPct = 0.0,
            bidsCount = 0,
            behavior = "Vencedor(es): $names · $SOURCE_TAG ${t.controlNumber}",
        )
    }

    /** Já existe registro desta licitação (importado antes, registrado à mão ou ao encerrar a sessão). */
    fun alreadyRecorded(existing: List<CompetitionRecord>, t: TrackedTender): Boolean {
        val number = normalizeNumber(t.number)
        return existing.any { r ->
            r.behavior.contains(t.controlNumber) || (r.portal == t.portal && number.isNotEmpty() && normalizeNumber(r.tenderNumber) == number)
        }
    }

    private fun normalizeNumber(n: String): String = n.uppercase().filter { it.isLetterOrDigit() || it == '/' }.removePrefix("PE")
}

/** Palavras da busca de mercado: palavras dos radares ativos ou, sem radar, o vocabulário do segmento da empresa. */
object MarketKeywords {
    fun from(radars: List<Radar>, segment: Segment, max: Int = 8): List<String> {
        val fromRadars = radars.flatMap { r -> r.keywords + listOfNotNull(r.preferredObject) }
            .map { it.trim() }.filter { it.length >= 3 }.distinctBy { it.lowercase() }
        if (fromRadars.isNotEmpty()) return fromRadars.take(max)
        val segments = radars.map { it.segment }.ifEmpty { listOf(segment) }.distinct()
        return segments.flatMap { SegmentAffinity.defaultKeywords(it) }.filter { it.length >= 3 }.distinct().take(max)
    }
}

/** Persistência JSON da base de mercado (DataStore). */
internal object MarketStore {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    const val MAX_RESULTS = 400

    @Serializable
    data class Row(
        val c: String, val i: Int, val d: String = "", val a: String = "", val o: String = "", val u: String = "",
        val n: String, val doc: String = "", val size: String? = null, val est: Double = 0.0, val hom: Double,
        val disc: Double? = null, val at: Long = 0L, val own: Boolean = false,
    )

    @Serializable
    data class ReportRow(
        val at: Long, val checked: Int, val imported: Int, val pending: Int, val market: Int,
        val partial: Boolean = false, val error: String? = null,
    )

    fun encode(list: List<PublicAwardResult>): String = json.encodeToString(
        ListSerializer(Row.serializer()),
        list.map {
            Row(
                it.controlNumber, it.itemNumber, it.itemDescription.take(200), it.agency.take(200), it.objectSummary.take(300), it.uf,
                it.supplierName, it.supplierDocument, it.supplierSize, it.estimatedValue, it.homologatedValue, it.publishedDiscountPct,
                it.resultDate, it.ownTender,
            )
        },
    )

    fun decode(text: String?): List<PublicAwardResult> {
        if (text.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(Row.serializer()), text) }.getOrDefault(emptyList()).map {
            PublicAwardResult(
                controlNumber = it.c, itemNumber = it.i, itemDescription = it.d, agency = it.a, objectSummary = it.o, uf = it.u,
                supplierName = it.n, supplierDocument = it.doc, supplierSize = it.size, estimatedValue = it.est,
                homologatedValue = it.hom, publishedDiscountPct = it.disc, resultDate = it.at, ownTender = it.own,
            )
        }
    }

    /** Novos substituem os antigos com a mesma chave; mantém os [MAX_RESULTS] mais recentes. */
    fun merge(previous: List<PublicAwardResult>, fresh: List<PublicAwardResult>): List<PublicAwardResult> {
        val map = LinkedHashMap<String, PublicAwardResult>()
        previous.forEach { map[it.key] = it }
        fresh.forEach { map[it.key] = it }
        return map.values.sortedByDescending { it.resultDate }.take(MAX_RESULTS)
    }

    fun encodeReport(r: CompetitionSyncReport): String = json.encodeToString(
        ReportRow.serializer(), ReportRow(r.finishedAt, r.checked, r.imported, r.pending, r.marketResults, r.partial, r.error),
    )

    fun decodeReport(text: String?): CompetitionSyncReport? {
        if (text.isNullOrBlank()) return null
        return runCatching { json.decodeFromString(ReportRow.serializer(), text) }.getOrNull()?.let {
            CompetitionSyncReport(it.at, it.checked, it.imported, it.pending, it.market, it.partial, it.error)
        }
    }
}
