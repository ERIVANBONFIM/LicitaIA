package com.licitaia.feature.tender

import androidx.compose.material.icons.outlined.Archive

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.licitaia.core.ui.components.ConfirmDialog
import com.licitaia.core.ui.components.EmptyState
import com.licitaia.core.ui.components.ErrorState
import com.licitaia.core.ui.components.LicitaCard
import com.licitaia.core.ui.components.LicitaScaffold
import com.licitaia.core.ui.components.PulsingDot
import com.licitaia.core.ui.components.SelectChip
import com.licitaia.core.ui.components.SkeletonList
import com.licitaia.core.ui.components.StatusBadge
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.components.color
import com.licitaia.core.ui.components.tone
import com.licitaia.core.ui.nav.LocalAppNavigator
import com.licitaia.core.ui.nav.Routes
import com.licitaia.core.ui.theme.LicitaColors
import com.licitaia.domain.model.Tender
import com.licitaia.domain.model.TenderStatus

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InterestsScreen(viewModel: TenderListViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    var filter by rememberSaveable { mutableStateOf<TenderStatus?>(null) }
    var toRemove by remember { mutableStateOf<Tender?>(null) }

    LaunchedEffect(viewModel) { viewModel.messages.collect(navigator::showMessage) }

    LicitaScaffold(title = "Licitações de Interesse", showBack = false) { padding ->
        val error = state.error
        val rows = state.rows.filter { it.tender.status != TenderStatus.DESCARTADA }
        when {
            state.loading -> SkeletonList(Modifier.padding(padding))
            error != null -> ErrorState(error, Modifier.padding(padding))
            rows.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = "Nenhuma licitação de interesse",
                    message = "Use \"Tenho Interesse\" na busca ou nos resultados do radar. A IA analisa o edital automaticamente.",
                    icon = Icons.Outlined.StarOutline,
                    actionLabel = "Buscar licitações",
                    onAction = { navigator.navigateTop(Routes.SEARCH) },
                )
            }
            else -> {
                val present = TenderStatus.entries.filter { s -> rows.any { it.tender.status == s } }
                val selected = filter?.takeIf { it in present }
                val visible = if (selected == null) rows else rows.filter { it.tender.status == selected }
                LazyColumn(
                    Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "filters") {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item(key = "all") { SelectChip("Todas (${rows.size})", selected == null, { filter = null }) }
                            items(present, key = { it.name }) { s ->
                                SelectChip(
                                    "${s.label} (${rows.count { it.tender.status == s }})", selected == s,
                                    { filter = if (selected == s) null else s }, color = s.tone().color(),
                                )
                            }
                        }
                    }
                    items(visible, key = { it.tender.id }) { row ->
                        InterestCard(
                            row,
                            modifier = Modifier.animateItem(),
                            onClick = { navigator.navigate(Routes.tender(row.tender.id)) },
                            onRemove = { toRemove = row.tender },
                            onArchive = { viewModel.archive(row.tender) },
                        )
                    }
                }
            }
        }
    }

    toRemove?.let { tender ->
        ConfirmDialog(
            title = "Remover interesse?",
            message = "${tender.number} — ${tender.agency}. A análise e as propostas associadas deixarão de ser acompanhadas.",
            confirmLabel = "Remover",
            tone = Tone.DANGER,
            icon = Icons.Outlined.DeleteOutline,
            onConfirm = { toRemove = null; viewModel.removeInterest(tender) },
            onDismiss = { toRemove = null },
        )
    }
}

@Composable
private fun InterestCard(row: TenderRow, modifier: Modifier, onClick: () -> Unit, onRemove: () -> Unit, onArchive: () -> Unit) {
    val tender = row.tender
    val analysis = row.analysis
    LicitaCard(modifier.fillMaxWidth(), onClick = onClick, accent = analysis?.recommendation?.tone()?.color()) {
        TenderHeadline(tender, analysis)
        Spacer(Modifier.height(10.dp))
        Text(tender.objectDescription, style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(10.dp))
        ValueDateStrip(tender)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (analysis != null) {
                StatusBadge(analysis.recommendation.label, analysis.recommendation.tone())
            } else if (tender.status != com.licitaia.domain.model.TenderStatus.EM_ANALISE) {
                // Nada é analisado sem pedido: só o selo neutro.
                StatusBadge("Sem análise por IA", Tone.NEUTRAL)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PulsingDot(LicitaColors.Blue, size = 8.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("IA analisando o edital…", style = MaterialTheme.typography.labelMedium, color = LicitaColors.BlueBright)
                }
            }
            Spacer(Modifier.width(8.dp))
            StatusBadge(deadlineLabel(tender.proposalDeadline), deadlineTone(tender.proposalDeadline))
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onArchive) {
                Icon(Icons.Outlined.Archive, contentDescription = "Arquivar licitação", tint = LicitaColors.TextMuted)
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = "Remover interesse", tint = LicitaColors.TextMuted)
            }
        }
    }
}
