package com.licitaia.feature.competition

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.licitaia.domain.competition.CompetitionResultsSync
import com.licitaia.domain.competition.CompetitionSyncReport
import com.licitaia.domain.competition.CompetitorRanking
import com.licitaia.domain.competition.MarketSnapshot
import com.licitaia.domain.competition.MarketSummary
import com.licitaia.domain.model.CompetitionRecord
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.Segment
import com.licitaia.domain.repository.AuthRepository
import com.licitaia.domain.repository.CompetitionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Desconto do fechamento em relação ao estimado, em %. */
internal val CompetitionRecord.discountPct: Double
    get() = if (estimatedValue <= 0.0) 0.0 else (estimatedValue - closingValue) / estimatedValue * 100.0

/** Registro importado automaticamente do PNCP (custo/margem e lances não são públicos). */
internal val CompetitionRecord.isPublicImport: Boolean
    get() = behavior.contains(CompetitionResultsSync.PUBLIC_RESULT_TAG)

/** A margem do registro é conhecida (registro manual/da sessão com nosso lance). */
internal val CompetitionRecord.hasKnownMargin: Boolean
    get() = !isPublicImport && ourFinalBid > 0.0

data class DiscountBand(val label: String, val count: Int, val wins: Int)

data class SegmentStats(
    val segment: Segment,
    val count: Int,
    val winRatePct: Int,
    val avgCompetitors: Double,
    val avgDiscountPct: Double,
    val avgClosing: Double,
    /** null = nenhum registro do segmento com margem conhecida. */
    val avgMarginPct: Double?,
)

data class BehaviorStat(val text: String, val count: Int)

data class CompetitionStats(
    val total: Int,
    val wins: Int,
    val losses: Int,
    val winRatePct: Double,
    val avgCompetitors: Double,
    val avgDiscountPct: Double,
    /** null = nenhum registro com margem conhecida (só resultados públicos). */
    val avgMarginPct: Double?,
    /** null = nenhum registro com lances contados. */
    val avgBids: Double?,
    /** Ordem cronológica, só registros com margem conhecida — base do gráfico de evolução da margem. */
    val timeline: List<CompetitionRecord>,
    val bands: List<DiscountBand>,
    val bySegment: List<SegmentStats>,
    val behaviors: List<BehaviorStat>,
)

data class CompetitionUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val noSession: Boolean = false,
    val hasAny: Boolean = false,
    val companyId: Long = 0,
    val companySegment: Segment? = null,
    val demo: Boolean = false,
    val segments: List<Segment> = emptyList(),
    val portals: List<Portal> = emptyList(),
    val segmentFilter: Segment? = null,
    val portalFilter: Portal? = null,
    /** Mais recente primeiro. */
    val records: List<CompetitionRecord> = emptyList(),
    val stats: CompetitionStats? = null,
    /** Gravação/exclusão em andamento. */
    val saving: Boolean = false,
    /** Busca de resultados no PNCP em andamento. */
    val syncing: Boolean = false,
    val lastReport: CompetitionSyncReport? = null,
    val market: MarketSummary = MarketSummary.EMPTY,
    val marketUpdatedAt: Long? = null,
    val marketKeywords: List<String> = emptyList(),
)

private data class Filters(val segment: Segment? = null, val portal: Portal? = null, val retry: Int = 0)

private data class SyncInfo(val report: CompetitionSyncReport? = null, val market: MarketSnapshot = MarketSnapshot(), val summary: MarketSummary = MarketSummary.EMPTY)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CompetitionViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val competition: CompetitionRepository,
    private val sync: CompetitionResultsSync,
) : ViewModel() {

    private val filters = MutableStateFlow(Filters())
    private val saving = MutableStateFlow(false)
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    private val remote = combine(auth.session, filters) { s, f -> s to f }
        .flatMapLatest { (session, f) ->
            if (session == null) {
                flowOf(CompetitionUiState(loading = false, noSession = true))
            } else {
                competition.observeRecords(session.activeCompany.id)
                    .map { all ->
                        // Filtro inexistente nesta empresa (ex.: após troca de empresa) é ignorado.
                        val segments = all.map { it.segment }.distinct().sortedBy { it.ordinal }
                        val portals = all.map { it.portal }.distinct().sortedBy { it.ordinal }
                        val segment = f.segment?.takeIf { it in segments }
                        val portal = f.portal?.takeIf { it in portals }
                        val filtered = all.filter { (segment == null || it.segment == segment) && (portal == null || it.portal == portal) }
                        CompetitionUiState(
                            loading = false, hasAny = all.isNotEmpty(), segments = segments, portals = portals,
                            companyId = session.activeCompany.id, companySegment = session.activeCompany.segment,
                            demo = session.user.demo || session.activeCompany.demo,
                            segmentFilter = segment, portalFilter = portal,
                            records = filtered.sortedByDescending { it.date },
                            stats = if (filtered.isEmpty()) null else computeStats(filtered),
                        )
                    }
                    .catch { emit(CompetitionUiState(loading = false, error = it.message ?: "Falha ao carregar o histórico.")) }
            }
        }

    private val syncInfo = auth.session.flatMapLatest { session ->
        if (session == null) flowOf(SyncInfo())
        else combine(sync.observeLastReport(session.activeCompany.id), sync.observeMarket(session.activeCompany.id)) { report, market ->
            SyncInfo(report, market, CompetitorRanking.summarize(market.results, session.activeCompany.cnpj))
        }.catch { emit(SyncInfo()) }
    }

    val state: StateFlow<CompetitionUiState> = combine(remote, saving, sync.running, syncInfo) { s, busy, running, info ->
        s.copy(
            saving = busy, syncing = running, lastReport = info.report,
            market = info.summary, marketUpdatedAt = info.market.updatedAt, marketKeywords = info.market.keywords,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CompetitionUiState())

    init {
        // Execução automática: ao abrir a tela, se a última atualização tem mais de 1 dia (o Worker diário também roda).
        viewModelScope.launch {
            auth.session.filterNotNull().map { it }.distinctUntilChanged { a, b -> a.activeCompany.id == b.activeCompany.id }.collect { session ->
                if (!session.user.demo && !session.activeCompany.demo) runCatching { sync.refreshIfDue(session.activeCompany.id) }
            }
        }
    }

    fun setSegment(segment: Segment?) = filters.update { it.copy(segment = segment) }
    fun setPortal(portal: Portal?) = filters.update { it.copy(portal = portal) }
    fun retry() = filters.update { it.copy(retry = it.retry + 1) }

    /** Botão "Atualizar resultados": consulta o PNCP agora. */
    fun refreshResults() {
        val session = auth.session.value ?: return
        if (session.user.demo || session.activeCompany.demo) {
            _messages.tryEmit("Na demonstração os resultados são de exemplo. Entre com a empresa real para buscar no PNCP.")
            return
        }
        if (sync.running.value) {
            _messages.tryEmit("A atualização de resultados já está em andamento.")
            return
        }
        viewModelScope.launch {
            val result = try {
                sync.refresh(session.activeCompany.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            result.fold(
                onSuccess = { _messages.tryEmit("Resultados atualizados: ${it.summary}.") },
                onFailure = { _messages.tryEmit(it.message?.takeIf(String::isNotBlank) ?: "Não foi possível consultar o PNCP agora.") },
            )
        }
    }

    /** Registro manual de resultado (formulário da tela Concorrência). */
    fun insert(record: CompetitionRecord) = run("Não foi possível registrar o resultado.") {
        competition.insert(record)
        _messages.tryEmit(if (record.won) "Vitória registrada." else "Derrota registrada.")
    }

    fun delete(record: CompetitionRecord) = run("Não foi possível excluir o registro.") {
        competition.delete(record.id)
        _messages.tryEmit("Registro de ${record.portal.shortName} ${record.tenderNumber} excluído.")
    }

    private fun run(errorMessage: String, block: suspend () -> Unit) {
        if (saving.value) return
        saving.value = true
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit(e.message?.takeIf(String::isNotBlank) ?: errorMessage)
            } finally {
                saving.value = false
            }
        }
    }
}

internal fun computeStats(records: List<CompetitionRecord>): CompetitionStats {
    val wins = records.count { it.won }
    val bandDefs = listOf(
        "0–5%" to (Double.NEGATIVE_INFINITY..5.0),
        "5–10%" to (5.0..10.0),
        "10–20%" to (10.0..20.0),
        "20–30%" to (20.0..30.0),
        "30%+" to (30.0..Double.POSITIVE_INFINITY),
    )
    val bands = bandDefs.mapIndexed { index, (label, range) ->
        val inBand = records.filter { r ->
            val d = r.discountPct
            // Limite superior exclusivo, exceto na última faixa.
            d >= range.start && (d < range.endInclusive || index == bandDefs.lastIndex) && (index == 0 || d >= range.start)
        }
        DiscountBand(label, inBand.size, inBand.count { it.won })
    }
    val withMargin = records.filter { it.hasKnownMargin }
    val withBids = records.filter { !it.isPublicImport && it.bidsCount > 0 }
    val bySegment = records.groupBy { it.segment }.map { (segment, list) ->
        SegmentStats(
            segment = segment, count = list.size,
            winRatePct = (list.count { it.won } * 100.0 / list.size).toInt(),
            avgCompetitors = list.map { it.competitors }.average(),
            avgDiscountPct = list.map { it.discountPct }.average(),
            avgClosing = list.map { it.closingValue }.average(),
            avgMarginPct = list.filter { it.hasKnownMargin }.map { it.ourMarginPct }.takeIf { it.isNotEmpty() }?.average(),
        )
    }.sortedByDescending { it.count }
    val behaviors = records.filter { it.behavior.isNotBlank() && !it.isPublicImport }
        .groupBy { it.behavior.trim() }
        .map { BehaviorStat(it.key, it.value.size) }
        .sortedByDescending { it.count }
        .take(6)
    return CompetitionStats(
        total = records.size, wins = wins, losses = records.size - wins,
        winRatePct = wins * 100.0 / records.size,
        avgCompetitors = records.map { it.competitors }.average(),
        avgDiscountPct = records.map { it.discountPct }.average(),
        avgMarginPct = withMargin.map { it.ourMarginPct }.takeIf { it.isNotEmpty() }?.average(),
        avgBids = withBids.map { it.bidsCount }.takeIf { it.isNotEmpty() }?.average(),
        timeline = withMargin.sortedBy { it.date },
        bands = bands, bySegment = bySegment, behaviors = behaviors,
    )
}
