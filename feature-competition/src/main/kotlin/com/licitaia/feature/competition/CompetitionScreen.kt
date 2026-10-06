package com.licitaia.feature.competition

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Percent
import androidx.compose.material.icons.outlined.TrendingDown
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.licitaia.core.ui.components.AlertBanner
import com.licitaia.core.ui.components.EmptyState
import com.licitaia.core.ui.components.ErrorState
import com.licitaia.core.ui.components.InfoRow
import com.licitaia.core.ui.components.LicitaCard
import com.licitaia.core.ui.components.LicitaScaffold
import com.licitaia.core.ui.components.LinearMeter
import com.licitaia.core.ui.components.PortalChip
import com.licitaia.core.ui.components.SectionHeader
import com.licitaia.core.ui.components.SelectChip
import com.licitaia.core.ui.components.SkeletonList
import com.licitaia.core.ui.components.StatCard
import com.licitaia.core.ui.components.StatusBadge
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.components.color
import com.licitaia.core.ui.theme.LicitaColors
import com.licitaia.domain.model.CompetitionRecord
import com.licitaia.domain.util.Formatters

@Composable
fun CompetitionScreen(viewModel: CompetitionViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LicitaScaffold(title = "Concorrência", showBack = false) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> SkeletonList()
                state.noSession -> ErrorState("Sessão encerrada. Entre novamente para ver a análise de concorrência.")
                state.error != null -> ErrorState(state.error ?: "", onRetry = viewModel::retry)
                !state.hasAny -> EmptyState(
                    "Sem histórico ainda",
                    "Quando a empresa participar de pregões, os resultados públicos e o histórico interno alimentarão esta análise.",
                    icon = Icons.Outlined.Insights,
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "notice") {
                        AlertBanner(
                            "Apenas dados públicos e históricos internos",
                            "A análise usa atas e resultados publicados pelos portais e o histórico de participações da própria empresa. Nenhum dado sigiloso de concorrentes é coletado.",
                            Tone.INFO,
                        )
                    }
                    item(key = "filters") { FiltersBlock(state, viewModel) }

                    val stats = state.stats
                    if (stats == null) {
                        item(key = "emptyFilter") {
                            EmptyState("Nada neste filtro", "Nenhum pregão do histórico corresponde ao segmento/portal selecionado.")
                        }
                    } else {
                        item(key = "kpi1") {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                StatCard(
                                    "Taxa de vitória", Formatters.percent(stats.winRatePct, 0), Icons.Outlined.EmojiEvents, Modifier.weight(1f),
                                    tone = if (stats.winRatePct >= 40) Tone.SUCCESS else Tone.WARNING,
                                )
                                StatCard("Concorrentes (média)", String.format(PT_BR, "%.1f", stats.avgCompetitors), Icons.Outlined.Groups, Modifier.weight(1f))
                            }
                        }
                        item(key = "kpi2") {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                StatCard("Desconto médio no fechamento vs. estimado", Formatters.percent(stats.avgDiscountPct), Icons.Outlined.TrendingDown, Modifier.weight(1f), tone = Tone.WARNING)
                                StatCard(
                                    "Margem média", Formatters.percent(stats.avgMarginPct), Icons.Outlined.Percent, Modifier.weight(1f),
                                    tone = if (stats.avgMarginPct >= 10) Tone.SUCCESS else if (stats.avgMarginPct >= 0) Tone.WARNING else Tone.DANGER,
                                )
                            }
                        }

                        item(key = "margin") {
                            ChartCard("Evolução da margem", "Margem do nosso lance final em cada pregão, em ordem cronológica") {
                                MarginChart(stats.timeline)
                            }
                        }
                        item(key = "bands") {
                            ChartCard("Faixas de fechamento", "Desconto do valor de fechamento em relação ao estimado") {
                                BandsChart(stats.bands)
                            }
                        }
                        item(key = "winloss") {
                            ChartCard("Vitórias × derrotas", "${stats.total} pregão(ões) no histórico filtrado") {
                                WinLossChart(stats.wins, stats.losses)
                            }
                        }

                        item(key = "segHeader") { SectionHeader("Média por objeto / segmento") }
                        items(stats.bySegment, key = { "seg-${it.segment.name}" }) { seg -> SegmentCard(seg) }

                        item(key = "behHeader") { SectionHeader("Comportamento de lances observado") }
                        item(key = "behavior") {
                            LicitaCard(Modifier.fillMaxWidth()) {
                                InfoRow("Lances por pregão (média)", String.format(PT_BR, "%.0f", stats.avgBids))
                                if (stats.behaviors.isEmpty()) {
                                    Text("Sem observações registradas.", style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
                                } else {
                                    stats.behaviors.forEach { b ->
                                        Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                                            Text("•", color = LicitaColors.Blue, modifier = Modifier.width(14.dp))
                                            Text(b.text, style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextPrimary, modifier = Modifier.weight(1f))
                                            if (b.count > 1) {
                                                Spacer(Modifier.width(8.dp))
                                                StatusBadge("${b.count}×", Tone.NEUTRAL)
                                            }
                                        }
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Text("Padrões inferidos de atas públicas; não identificam estratégia sigilosa de terceiros.", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
                            }
                        }

                        item(key = "histHeader") { SectionHeader("Histórico (${state.records.size})") }
                        items(state.records.size, key = { i -> "rec-${state.records[i].id}-$i" }) { i ->
                            RecordCard(state.records[i], Modifier.animateItem())
                        }
                    }
                }
            }
        }
    }
}

internal val PT_BR = java.util.Locale("pt", "BR")

@Composable
private fun FiltersBlock(state: CompetitionUiState, viewModel: CompetitionViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.segments.size > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { SelectChip("Todos os segmentos", state.segmentFilter == null, { viewModel.setSegment(null) }) }
                items(state.segments) { s -> SelectChip(s.label, state.segmentFilter == s, { viewModel.setSegment(s) }) }
            }
        }
        if (state.portals.size > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { SelectChip("Todos os portais", state.portalFilter == null, { viewModel.setPortal(null) }) }
                items(state.portals) { p -> SelectChip(p.shortName, state.portalFilter == p, { viewModel.setPortal(p) }, color = p.color()) }
            }
        }
    }
}

@Composable
private fun ChartCard(title: String, subtitle: String, content: @Composable () -> Unit) {
    LicitaCard(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = LicitaColors.TextPrimary)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
        Spacer(Modifier.height(14.dp))
        content()
    }
}

@Composable
private fun SegmentCard(seg: SegmentStats) {
    LicitaCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(seg.segment.label, style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary, modifier = Modifier.weight(1f))
            StatusBadge("${seg.count} pregão(ões)", Tone.NEUTRAL)
        }
        Spacer(Modifier.height(10.dp))
        LinearMeter("Taxa de vitória", seg.winRatePct, valueText = "${seg.winRatePct}%")
        Spacer(Modifier.height(6.dp))
        InfoRow("Concorrentes (média)", String.format(PT_BR, "%.1f", seg.avgCompetitors))
        InfoRow("Desconto médio no fechamento", Formatters.percent(seg.avgDiscountPct))
        InfoRow("Fechamento médio", Formatters.brl(seg.avgClosing))
        InfoRow("Margem média", Formatters.percent(seg.avgMarginPct), valueColor = if (seg.avgMarginPct >= 0) LicitaColors.GreenBright else LicitaColors.RedBright)
    }
}

@Composable
private fun RecordCard(record: CompetitionRecord, modifier: Modifier) {
    LicitaCard(modifier.fillMaxWidth(), accent = if (record.won) LicitaColors.Green else null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PortalChip(record.portal)
            Text(record.tenderNumber, style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextSecondary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            StatusBadge(if (record.won) "Vitória" else "Derrota", if (record.won) Tone.SUCCESS else Tone.DANGER)
        }
        Spacer(Modifier.height(8.dp))
        Text(record.objectSummary, style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            "${record.agency} · ${record.segment.label} · ${Formatters.date(record.date)}",
            style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Metric("Estimado", Formatters.brlCompact(record.estimatedValue), Modifier.weight(1f))
            Metric("Fechamento", Formatters.brlCompact(record.closingValue), Modifier.weight(1f))
            Metric("Nosso lance", Formatters.brlCompact(record.ourFinalBid), Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Metric("Desconto", Formatters.percent(record.discountPct), Modifier.weight(1f))
            Metric("Margem", Formatters.percent(record.ourMarginPct), Modifier.weight(1f), if (record.ourMarginPct >= 0) LicitaColors.GreenBright else LicitaColors.RedBright)
            Metric("Concorrentes", "${record.competitors} · ${record.bidsCount} lances", Modifier.weight(1f))
        }
        if (record.behavior.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(record.behavior, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier, color: androidx.compose.ui.graphics.Color = LicitaColors.TextPrimary) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted, maxLines = 1)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = color, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
