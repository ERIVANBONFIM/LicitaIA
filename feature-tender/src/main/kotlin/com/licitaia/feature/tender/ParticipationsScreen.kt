package com.licitaia.feature.tender

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.automirrored.outlined.TrendingDown
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.licitaia.core.ui.components.EmptyState
import com.licitaia.core.ui.components.ErrorState
import com.licitaia.core.ui.components.LicitaCard
import com.licitaia.core.ui.components.LicitaScaffold
import com.licitaia.core.ui.components.SecondaryButton
import com.licitaia.core.ui.components.SimulationBadge
import com.licitaia.core.ui.components.SkeletonList
import com.licitaia.core.ui.components.StatCard
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
fun ParticipationsScreen(viewModel: TenderListViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current

    LicitaScaffold(title = "Minhas Participações", showBack = false) { padding ->
        val error = state.error
        val rows = state.rows.filter { it.tender.status.isParticipation() }
        when {
            state.loading -> SkeletonList(Modifier.padding(padding))
            error != null -> ErrorState(error, Modifier.padding(padding))
            rows.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = "Nenhuma participação ainda",
                    message = "Licitações com proposta enviada para aprovação, em disputa ou já decididas aparecem aqui.",
                    icon = Icons.Outlined.Gavel,
                    actionLabel = "Ver licitações de interesse",
                    onAction = { navigator.navigateTop(Routes.INTERESTS) },
                )
            }
            else -> {
                val won = rows.filter { it.tender.status == TenderStatus.VENCIDA }
                val lost = rows.count { it.tender.status == TenderStatus.PERDIDA }
                val ongoing = rows.size - won.size - lost
                LazyColumn(
                    Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "stats") {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            StatCard("Em andamento", "$ongoing", Icons.Outlined.HourglassEmpty, Modifier.weight(1f), tone = Tone.INFO, highlight = ongoing > 0)
                            StatCard("Vencidas", "${won.size}", Icons.Outlined.EmojiEvents, Modifier.weight(1f), tone = Tone.SUCCESS)
                            StatCard("Perdidas", "$lost", Icons.AutoMirrored.Outlined.TrendingDown, Modifier.weight(1f), tone = if (lost > 0) Tone.DANGER else Tone.NEUTRAL)
                        }
                    }
                    if (won.isNotEmpty()) {
                        item(key = "won-total") {
                            Text(
                                "Valor estimado conquistado: ${Formatters.brl(won.sumOf { it.tender.estimatedValue })}",
                                style = MaterialTheme.typography.labelMedium, color = LicitaColors.GreenBright,
                            )
                        }
                    }
                    items(rows, key = { it.tender.id }) { row ->
                        val tender = row.tender
                        val result = when (tender.status) {
                            TenderStatus.VENCIDA -> "Vencemos" to Tone.SUCCESS
                            TenderStatus.PERDIDA -> "Não vencemos" to Tone.DANGER
                            TenderStatus.EM_DISPUTA -> "Em disputa" to Tone.INFO
                            TenderStatus.ENVIADA_SIMULADA -> "Aguardando sessão" to Tone.INFO
                            else -> "Em andamento" to Tone.WARNING
                        }
                        LicitaCard(
                            Modifier.fillMaxWidth().animateItem(),
                            onClick = { navigator.navigate(Routes.tender(tender.id)) },
                            accent = if (tender.status == TenderStatus.VENCIDA) LicitaColors.Green else if (tender.status == TenderStatus.PERDIDA) LicitaColors.Red else null,
                        ) {
                            TenderHeadline(tender, row.analysis)
                            Spacer(Modifier.height(8.dp))
                            Text(tender.objectDescription, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(10.dp))
                            ValueDateStrip(tender)
                            Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StatusBadge("Resultado: ${result.first}", result.second, pulsing = tender.status == TenderStatus.EM_DISPUTA)
                                Unit
                                Spacer(Modifier.weight(1f))
                                Text("Atualizada ${Formatters.relative(tender.updatedAt)}", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
                            }
                            if (tender.status != TenderStatus.VENCIDA && tender.status != TenderStatus.PERDIDA) {
                                Spacer(Modifier.height(8.dp))
                                SecondaryButton(
                                    "Registrar resultado (vencemos / perdemos)", { navigator.navigate(Routes.tender(tender.id)) },
                                    Modifier.fillMaxWidth(), icon = Icons.Outlined.EmojiEvents, tone = Tone.NEUTRAL,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
