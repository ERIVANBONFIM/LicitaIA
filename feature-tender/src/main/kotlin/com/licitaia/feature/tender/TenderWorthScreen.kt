package com.licitaia.feature.tender

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.Balance
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.RequestQuote
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.licitaia.core.ui.components.AlertBanner
import com.licitaia.core.ui.components.ButtonRow
import com.licitaia.core.ui.components.EmptyState
import com.licitaia.core.ui.components.ErrorState
import com.licitaia.core.ui.components.GradientCard
import com.licitaia.core.ui.components.IconBubble
import com.licitaia.core.ui.components.InfoRow
import com.licitaia.core.ui.components.LicitaCard
import com.licitaia.core.ui.components.LicitaScaffold
import com.licitaia.core.ui.components.LinearMeter
import com.licitaia.core.ui.components.PrimaryButton
import com.licitaia.core.ui.components.ScoreRing
import com.licitaia.core.ui.components.SecondaryButton
import com.licitaia.core.ui.components.SectionHeader
import com.licitaia.core.ui.components.SkeletonList
import com.licitaia.core.ui.components.StatusBadge
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.components.color
import com.licitaia.core.ui.components.tone
import com.licitaia.core.ui.nav.LocalAppNavigator
import com.licitaia.core.ui.nav.Routes
import com.licitaia.core.ui.theme.LicitaColors
import com.licitaia.domain.model.Recommendation
import com.licitaia.domain.util.Formatters

@Composable
fun TenderWorthScreen(viewModel: TenderAnalysisViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val tender = state.tender
    val analysis = state.analysis

    LicitaScaffold(title = "Vale a pena participar?", subtitle = tender?.number, showBack = true) { padding ->
        when {
            state.loading -> SkeletonList(Modifier.padding(padding))
            state.notFound || tender == null -> ErrorState("Esta licitação não existe ou pertence a outra empresa.", Modifier.padding(padding), title = "Licitação não encontrada")
            analysis == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = "Análise ainda não concluída",
                    message = "O veredito executivo é gerado a partir da análise do edital pela IA.",
                    icon = Icons.Outlined.Balance,
                    actionLabel = "Acompanhar análise",
                    onAction = { navigator.navigate(Routes.tenderAnalysis(tender.id)) },
                )
            }
            else -> {
                val fit = analysis.fit
                val tone = analysis.recommendation.tone()
                LazyColumn(
                    Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (analysis.heuristicOnly) {
                        item(key = "heuristic") {
                            HeuristicAnalysisBanner(
                                activeAi = state.activeAi, canAnalyze = state.canAnalyze, analyzing = state.analyzing,
                                onReanalyze = viewModel::analyze, onConfigure = { navigator.navigate(Routes.AI_SETTINGS) },
                                detail = "Veredito calculado por regras locais, sem leitura do edital.",
                            )
                        }
                    }
                    item(key = "verdict") {
                        GradientCard(Modifier.fillMaxWidth(), brush = verdictBrush(analysis.recommendation)) {
                            Text(
                                "${tender.portal.shortName} · ${tender.number}",
                                style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextSecondary,
                            )
                            Text(tender.agency, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary, maxLines = 1)
                            Spacer(Modifier.height(18.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ScoreRing(fit.overall, size = 112.dp, strokeWidth = 10.dp, label = "score geral")
                                Spacer(Modifier.width(18.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Veredito da IA", style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextSecondary)
                                    Text(
                                        analysis.recommendation.label,
                                        style = MaterialTheme.typography.headlineMedium, color = tone.color(), fontWeight = FontWeight.Black,
                                        lineHeight = MaterialTheme.typography.headlineMedium.lineHeight,
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    StatusBadge("Margem estimada ${Formatters.percent(fit.estimatedMarginPct)}", marginTone(fit.estimatedMarginPct))
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                            Text("Justificativa", style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextSecondary)
                            Spacer(Modifier.height(4.dp))
                            Text(analysis.justification, style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextPrimary)
                        }
                    }
                    item(key = "adherence-h") { SectionHeader("Aderência") }
                    item(key = "adherence") {
                        LicitaCard(Modifier.fillMaxWidth()) {
                            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                LinearMeter("Aderência técnica", fit.technical)
                                LinearMeter("Aderência documental", fit.documentary)
                                LinearMeter("Aderência financeira", fit.financial)
                                LinearMeter("Prazo (conforto de execução)", fit.deadlineFit)
                                LinearMeter("Compatibilidade geográfica", fit.geographicFit)
                            }
                        }
                    }
                    item(key = "finance-h") { SectionHeader("Financeiro e concorrência") }
                    item(key = "finance") {
                        LicitaCard(Modifier.fillMaxWidth()) {
                            MetricRow(
                                Icons.Outlined.Payments, "Margem estimada", Formatters.percent(fit.estimatedMarginPct),
                                marginTone(fit.estimatedMarginPct), marginHint(fit.estimatedMarginPct),
                            )
                            MetricRow(
                                Icons.Outlined.RequestQuote, "Necessidade de investimento", Formatters.brl(fit.investmentNeeded),
                                investmentTone(fit.investmentNeeded, tender.estimatedValue),
                                if (fit.investmentNeeded <= 0.0) "Sem investimento prévio" else "${Formatters.percent(fit.investmentNeeded / tender.estimatedValue.coerceAtLeast(1.0) * 100)} do valor estimado",
                            )
                            MetricRow(
                                Icons.Outlined.Groups, "Concorrência histórica", "${fit.historicalCompetitors} concorrente(s)",
                                competitorsTone(fit.historicalCompetitors), competitorsHint(fit.historicalCompetitors),
                            )
                            Spacer(Modifier.height(6.dp))
                            InfoRow("Faixa de preço sugerida", "${Formatters.brlCompact(analysis.priceRange.min)} – ${Formatters.brlCompact(analysis.priceRange.max)}")
                            InfoRow("Preço recomendado", Formatters.brl(analysis.priceRange.suggested), valueColor = LicitaColors.GreenBright)
                            InfoRow("Valor estimado do órgão", Formatters.brl(tender.estimatedValue))
                        }
                    }
                    item(key = "risk-h") { SectionHeader("Riscos") }
                    item(key = "risk") {
                        LicitaCard(Modifier.fillMaxWidth()) {
                            RiskRow("Risco operacional", fit.operationalRisk)
                            RiskRow("Risco documental", fit.documentaryRisk)
                            RiskRow("Risco contratual", fit.contractualRisk)
                        }
                    }
                    item(key = "critical-h") { SectionHeader("Pontos críticos") }
                    item(key = "critical") {
                        LicitaCard(Modifier.fillMaxWidth(), accent = if (analysis.criticalPoints.isNotEmpty()) LicitaColors.Yellow else null) {
                            if (analysis.criticalPoints.isEmpty()) {
                                Text("Nenhum ponto crítico identificado.", style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextSecondary)
                            } else {
                                analysis.criticalPoints.forEachIndexed { index, point ->
                                    Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
                                        Box(
                                            Modifier.size(22.dp).clip(CircleShape).background(LicitaColors.Yellow.copy(alpha = 0.18f)),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text("${index + 1}", style = MaterialTheme.typography.labelSmall, color = LicitaColors.Yellow, fontWeight = FontWeight.Bold)
                                        }
                                        Spacer(Modifier.width(10.dp))
                                        Text(point, style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextPrimary, modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                    item(key = "footer") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "Gerado por ${analysis.providerName} em ${Formatters.dateTime(analysis.generatedAt)}. A decisão final é sempre humana.",
                                style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                            )
                            ButtonRow {
                                SecondaryButton("Ver análise", { navigator.navigate(Routes.tenderAnalysis(tender.id)) }, Modifier.weight(1f), icon = Icons.Outlined.Analytics)
                                PrimaryButton(
                                    "Gerar proposta", { navigator.navigate(Routes.tenderProposal(tender.id)) }, Modifier.weight(1f),
                                    tone = if (analysis.recommendation == Recommendation.NAO_PARTICIPAR) Tone.WARNING else Tone.SUCCESS,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun verdictBrush(r: Recommendation): Brush = when (r) {
    Recommendation.PARTICIPAR -> Brush.linearGradient(listOf(Color(0xFF0B2E2A), Color(0xFF0F1F3F)))
    Recommendation.AVALIAR -> Brush.linearGradient(listOf(Color(0xFF3A2A08), Color(0xFF0F1F3F)))
    Recommendation.NAO_PARTICIPAR -> Brush.linearGradient(listOf(Color(0xFF3B1212), Color(0xFF0F1F3F)))
}

private fun marginTone(pct: Double): Tone = when {
    pct >= 20 -> Tone.SUCCESS
    pct >= 10 -> Tone.WARNING
    else -> Tone.DANGER
}

private fun marginHint(pct: Double): String = when {
    pct >= 20 -> "Margem confortável"
    pct >= 10 -> "Margem apertada — atenção ao piso"
    pct > 0 -> "Margem mínima — risco de prejuízo em disputa"
    else -> "Sem margem"
}

private fun investmentTone(investment: Double, estimated: Double): Tone = when {
    investment <= 0.0 -> Tone.SUCCESS
    investment <= estimated * 0.10 -> Tone.INFO
    investment <= estimated * 0.25 -> Tone.WARNING
    else -> Tone.DANGER
}

private fun competitorsTone(n: Int): Tone = when {
    n <= 3 -> Tone.SUCCESS
    n <= 6 -> Tone.WARNING
    else -> Tone.DANGER
}

private fun competitorsHint(n: Int): String = when {
    n <= 3 -> "Disputa pouco concorrida"
    n <= 6 -> "Disputa moderada"
    else -> "Disputa acirrada — preço tende a cair"
}

@Composable
private fun MetricRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, tone: Tone, hint: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBubble(icon, tone.color(), size = 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextSecondary)
            Text(hint, style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
        }
        Text(value, style = MaterialTheme.typography.titleSmall, color = tone.color(), fontWeight = FontWeight.Bold)
    }
}
