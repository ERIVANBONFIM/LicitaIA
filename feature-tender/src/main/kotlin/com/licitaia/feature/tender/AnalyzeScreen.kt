package com.licitaia.feature.tender

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.licitaia.core.ui.components.ButtonRow
import com.licitaia.core.ui.components.EmptyState
import com.licitaia.core.ui.components.ErrorState
import com.licitaia.core.ui.components.GradientCard
import com.licitaia.core.ui.components.IconBubble
import com.licitaia.core.ui.components.LicitaCard
import com.licitaia.core.ui.components.LicitaScaffold
import com.licitaia.core.ui.components.PrimaryButton
import com.licitaia.core.ui.components.PulsingDot
import com.licitaia.core.ui.components.SecondaryButton
import com.licitaia.core.ui.components.SkeletonList
import com.licitaia.core.ui.components.StatusBadge
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.components.tone
import com.licitaia.core.ui.nav.LocalAppNavigator
import com.licitaia.core.ui.nav.Routes
import com.licitaia.core.ui.theme.LicitaColors
import com.licitaia.domain.model.TenderStatus
import com.licitaia.domain.util.Formatters

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AnalyzeScreen(viewModel: TenderListViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current

    LaunchedEffect(viewModel) { viewModel.messages.collect(navigator::showMessage) }

    LicitaScaffold(title = "Analisar Edital", showBack = false) { padding ->
        val error = state.error
        val rows = state.rows.filter { it.tender.status != TenderStatus.DESCARTADA }
        when {
            state.loading -> SkeletonList(Modifier.padding(padding))
            error != null -> ErrorState(error, Modifier.padding(padding))
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "intro") {
                    GradientCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconBubble(Icons.Outlined.AutoAwesome, LicitaColors.BlueBright)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Análise de edital com IA", style = MaterialTheme.typography.titleMedium, color = LicitaColors.TextPrimary)
                                Text(
                                    "Escolha uma licitação de interesse para analisar ou reanalisar. A IA extrai exigências, documentos, prazos e calcula o score de aderência.",
                                    style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary,
                                )
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        PrimaryButton(
                            "Cadastrar licitação manualmente", { navigator.navigate(Routes.TENDER_NEW) }, Modifier.fillMaxWidth(),
                            enabled = state.canAnalyze, icon = Icons.Outlined.EditNote,
                        )
                        Spacer(Modifier.height(8.dp))
                        SecondaryButton("Buscar nova licitação", { navigator.navigateTop(Routes.SEARCH) }, Modifier.fillMaxWidth(), icon = Icons.Outlined.Search)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Para analisar um edital real, cadastre a licitação (ou marque interesse) e importe o PDF do edital na tela da licitação.",
                            style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted,
                        )
                    }
                }
                if (!state.canAnalyze) {
                    item(key = "rbac") {
                        Text(
                            "Seu perfil (${state.role?.label ?: "—"}) pode consultar análises, mas não iniciar novas.",
                            style = MaterialTheme.typography.labelMedium, color = LicitaColors.Yellow,
                        )
                    }
                }
                if (rows.isEmpty()) {
                    item(key = "empty") {
                        EmptyState(
                            title = "Nenhuma licitação para analisar",
                            message = "Marque \"Tenho Interesse\" em uma licitação para habilitar a análise.",
                            icon = Icons.Outlined.Analytics,
                        )
                    }
                } else {
                    items(rows, key = { it.tender.id }) { row ->
                        val tender = row.tender
                        val analysis = row.analysis
                        val busy = tender.id in state.busy
                        LicitaCard(Modifier.fillMaxWidth().animateItem(), onClick = { navigator.navigate(Routes.tenderAnalysis(tender.id)) }) {
                            TenderHeadline(tender, analysis)
                            Spacer(Modifier.height(8.dp))
                            Text(tender.objectDescription, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                when {
                                    busy -> {
                                        PulsingDot(LicitaColors.Blue, size = 8.dp)
                                        Spacer(Modifier.width(6.dp))
                                        Text("IA analisando…", style = MaterialTheme.typography.labelMedium, color = LicitaColors.BlueBright)
                                    }
                                    analysis != null -> {
                                        StatusBadge(analysis.recommendation.label, analysis.recommendation.tone())
                                        Spacer(Modifier.width(8.dp))
                                        if (analysis.heuristicOnly) {
                                            StatusBadge("Heurística", Tone.WARNING)
                                            Spacer(Modifier.width(6.dp))
                                        }
                                        Text(
                                            "Analisada ${Formatters.relative(analysis.generatedAt)} · ${analysis.providerName}",
                                            style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    tender.isManual && !tender.hasEditalText -> StatusBadge("Sem edital", Tone.WARNING)
                                    tender.status == TenderStatus.EM_ANALISE -> {
                                        PulsingDot(LicitaColors.Blue, size = 8.dp)
                                        Spacer(Modifier.width(6.dp))
                                        Text("Análise em andamento", style = MaterialTheme.typography.labelMedium, color = LicitaColors.BlueBright)
                                    }
                                    else -> StatusBadge("Sem análise", Tone.WARNING)
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            ButtonRow {
                                if (analysis == null) {
                                    PrimaryButton(
                                        "Analisar agora", { viewModel.analyze(tender) }, Modifier.weight(1f),
                                        enabled = state.canAnalyze, loading = busy, icon = Icons.Outlined.AutoAwesome,
                                    )
                                } else {
                                    SecondaryButton("Ver análise", { navigator.navigate(Routes.tenderAnalysis(tender.id)) }, Modifier.weight(1f), icon = Icons.Outlined.Analytics)
                                    SecondaryButton(
                                        "Reanalisar", { viewModel.analyze(tender) }, Modifier.weight(0.9f),
                                        enabled = state.canAnalyze && !busy, icon = Icons.Outlined.Refresh, tone = Tone.NEUTRAL,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
