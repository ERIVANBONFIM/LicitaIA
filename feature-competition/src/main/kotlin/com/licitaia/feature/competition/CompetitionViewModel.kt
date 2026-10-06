package com.licitaia.feature.competition

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.licitaia.domain.model.CompetitionRecord
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.Segment
import com.licitaia.domain.repository.AuthRepository
import com.licitaia.domain.repository.CompetitionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/** Desconto do fechamento em relação ao estimado, em %. */
internal val CompetitionRecord.discountPct: Double
    get() = if (estimatedValue <= 0.0) 0.0 else (estimatedValue - closingValue) / estimatedValue * 100.0

data class DiscountBand(val label: String, val count: Int, val wins: Int)

data class SegmentStats(
    val segment: Segment,
    val count: Int,
    val winRatePct: Int,
    val avgCompetitors: Double,
    val avgDiscountPct: Double,
    val avgClosing: Double,
    val avgMarginPct: Double,
)

data class BehaviorStat(val text: String, val count: Int)

data class CompetitionStats(
    val total: Int,
    val wins: Int,
    val losses: Int,
    val winRatePct: Double,
    val avgCompetitors: Double,
    val avgDiscountPct: Double,
    val avgMarginPct: Double,
    val avgBids: Double,
    /** Ordem cronológica — base do gráfico de evolução da margem. */
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
    val segments: List<Segment> = emptyList(),
    val portals: List<Portal> = emptyList(),
    val segmentFilter: Segment? = null,
    val portalFilter: Portal? = null,
    /** Mais recente primeiro. */
    val records: List<CompetitionRecord> = emptyList(),
    val stats: CompetitionStats? = null,
)

private data class Filters(val segment: Segment? = null, val portal: Portal? = null, val retry: Int = 0)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CompetitionViewModel @Inject constructor(
    auth: AuthRepository,
    private val competition: CompetitionRepository,
) : ViewModel() {

    private val filters = MutableStateFlow(Filters())

    val state: StateFlow<CompetitionUiState> = combine(auth.session, filters) { s, f -> s to f }
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
                            segmentFilter = segment, portalFilter = portal,
                            records = filtered.sortedByDescending { it.date },
                            stats = if (filtered.isEmpty()) null else computeStats(filtered),
                        )
                    }
                    .catch { emit(CompetitionUiState(loading = false, error = it.message ?: "Falha ao carregar o histórico.")) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CompetitionUiState())

    fun setSegment(segment: Segment?) = filters.update { it.copy(segment = segment) }
    fun setPortal(portal: Portal?) = filters.update { it.copy(portal = portal) }
    fun retry() = filters.update { it.copy(retry = it.retry + 1) }
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
    val bySegment = records.groupBy { it.segment }.map { (segment, list) ->
        SegmentStats(
            segment = segment, count = list.size,
            winRatePct = (list.count { it.won } * 100.0 / list.size).toInt(),
            avgCompetitors = list.map { it.competitors }.average(),
            avgDiscountPct = list.map { it.discountPct }.average(),
            avgClosing = list.map { it.closingValue }.average(),
            avgMarginPct = list.map { it.ourMarginPct }.average(),
        )
    }.sortedByDescending { it.count }
    val behaviors = records.filter { it.behavior.isNotBlank() }
        .groupBy { it.behavior.trim() }
        .map { BehaviorStat(it.key, it.value.size) }
        .sortedByDescending { it.count }
        .take(6)
    return CompetitionStats(
        total = records.size, wins = wins, losses = records.size - wins,
        winRatePct = wins * 100.0 / records.size,
        avgCompetitors = records.map { it.competitors }.average(),
        avgDiscountPct = records.map { it.discountPct }.average(),
        avgMarginPct = records.map { it.ourMarginPct }.average(),
        avgBids = records.map { it.bidsCount }.average(),
        timeline = records.sortedBy { it.date },
        bands = bands, bySegment = bySegment, behaviors = behaviors,
    )
}
