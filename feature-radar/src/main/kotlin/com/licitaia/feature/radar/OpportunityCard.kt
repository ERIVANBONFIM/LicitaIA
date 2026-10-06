package com.licitaia.feature.radar

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.licitaia.core.ui.components.ButtonRow
import com.licitaia.core.ui.components.EmptyState
import com.licitaia.core.ui.components.ErrorState
import com.licitaia.core.ui.components.LicitaCard
import com.licitaia.core.ui.components.PortalChip
import com.licitaia.core.ui.components.PrimaryButton
import com.licitaia.core.ui.components.ScoreRing
import com.licitaia.core.ui.components.SecondaryButton
import com.licitaia.core.ui.components.SkeletonList
import com.licitaia.core.ui.components.StatusBadge
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.nav.LocalAppNavigator
import com.licitaia.core.ui.theme.LicitaColors
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.ScoredOpportunity
import com.licitaia.domain.util.Formatters

/** Encaminha mensagens e navegações emitidas pelo ViewModel. */
@Composable
internal fun OpportunityEvents(viewModel: OpportunityListViewModel) {
    val navigator = LocalAppNavigator.current
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is OpportunityEvent.Message -> navigator.showMessage(event.text)
                is OpportunityEvent.Navigate -> navigator.navigate(event.route)
            }
        }
    }
}

/** Itens de lista (loading / erro / vazio / cards) compartilhados por busca e resultados de radar. */
@OptIn(ExperimentalFoundationApi::class)
internal fun LazyListScope.opportunityItems(
    state: OpportunityListState,
    viewModel: OpportunityListViewModel,
    emptyTitle: String,
    emptyMessage: String,
    emptyIcon: ImageVector = Icons.Outlined.SearchOff,
) {
    val error = state.error
    when {
        state.loading -> item(key = "loading") { SkeletonList(Modifier.padding(horizontal = 0.dp), items = 3) }
        error != null -> item(key = "error") {
            ErrorState(
                error,
                title = if (isConnectivityError(error)) "Sem conexão com o PNCP" else "Não foi possível concluir",
                onRetry = viewModel::reload,
            )
        }
        state.items.isEmpty() -> item(key = "empty") { EmptyState(emptyTitle, emptyMessage, icon = emptyIcon) }
        else -> {
            item(key = "count") {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        "${state.items.size} oportunidade(s) encontrada(s)",
                        style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextSecondary,
                    )
                    Text(
                        sourceLabel(state.items),
                        style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted,
                    )
                }
            }
            items(state.items, key = { it.opportunity.id }) { item ->
                OpportunityCard(
                    item = item,
                    busy = item.opportunity.id in state.busy,
                    canAnalyze = state.canAnalyze,
                    onInterest = { viewModel.onInterest(item) },
                    onAnalyze = { viewModel.onAnalyze(item) },
                    modifier = Modifier.padding(horizontal = 16.dp).animateItem(),
                )
            }
        }
    }
}

/** Rotula a origem dos resultados. Hoje a única fonte real é o PNCP (API pública de consulta). */
internal fun sourceLabel(items: List<ScoredOpportunity>): String {
    val portals = items.map { it.opportunity.portal }.distinct()
    return when {
        portals.size == 1 && portals.single() == Portal.PNCP -> "Fonte: PNCP · consulta pública (dados oficiais, sem login)"
        portals.isEmpty() -> "Fonte: —"
        else -> "Fonte: " + portals.joinToString(", ") { it.displayName }
    }
}

internal fun isConnectivityError(message: String): Boolean {
    val m = message.lowercase()
    return "conex" in m || "internet" in m || "demorou" in m || "indispon" in m || "offline" in m
}

@Composable
internal fun OpportunityCard(
    item: ScoredOpportunity,
    busy: Boolean,
    canAnalyze: Boolean,
    onInterest: () -> Unit,
    onAnalyze: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val op = item.opportunity
    LicitaCard(modifier.fillMaxWidth(), accent = if (item.interested) LicitaColors.Green else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScoreRing(item.score, size = 58.dp, strokeWidth = 5.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PortalChip(op.portal)
                    StatusBadge(op.modality.label, Tone.NEUTRAL)
                }
                Spacer(Modifier.height(6.dp))
                Text(op.number, style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(op.agency, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(op.objectDescription, style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Valor estimado", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
                Text(Formatters.brl(op.estimatedValue), style = MaterialTheme.typography.titleMedium, color = LicitaColors.GreenBright, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Icon(Icons.Outlined.Place, contentDescription = null, tint = LicitaColors.TextMuted, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text("${op.city}/${op.uf}", style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextSecondary, maxLines = 1)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            DateCell("Publicação", Formatters.date(op.publishedAt), Modifier.weight(1f))
            DateCell("Propostas até", Formatters.dateTime(op.proposalDeadline), Modifier.weight(1.3f))
            DateCell("Sessão", Formatters.dateTime(op.sessionAt), Modifier.weight(1.3f))
        }
        if (op.requiresLocalSupport || item.interested) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (item.interested) StatusBadge("Em interesse", Tone.SUCCESS)
                if (op.requiresLocalSupport) StatusBadge("Exige atendimento local", Tone.WARNING)
            }
        }
        Spacer(Modifier.height(12.dp))
        ButtonRow {
            if (item.interested) {
                SecondaryButton("Em interesse ✓", onInterest, Modifier.weight(1f), enabled = !busy, icon = Icons.Outlined.Check, tone = Tone.SUCCESS)
            } else {
                PrimaryButton("Tenho Interesse", onInterest, Modifier.weight(1f), loading = busy, icon = Icons.Outlined.StarOutline)
            }
            SecondaryButton("Analisar", onAnalyze, Modifier.weight(0.8f), enabled = !busy && canAnalyze, icon = Icons.Outlined.Analytics)
        }
        if (item.interested) {
            Text(
                "Toque em \"Em interesse ✓\" para abrir a licitação.",
                style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted, modifier = Modifier.padding(top = 6.dp),
            )
        }
        if (!canAnalyze) {
            Text(
                "Seu perfil não tem permissão para analisar editais.",
                style = MaterialTheme.typography.labelSmall, color = LicitaColors.Yellow, modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun DateCell(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted, maxLines = 1)
        Text(value, style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
