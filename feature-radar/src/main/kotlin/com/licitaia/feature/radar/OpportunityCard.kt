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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
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

/**
 * Puxar para atualizar + atualização automática a cada 2 min enquanto a tela está visível (STARTED);
 * o laço é cancelado ao sair da tela ou ir para segundo plano.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OpportunityRefreshBox(
    state: OpportunityListState,
    viewModel: OpportunityListViewModel,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.autoRefreshLoop() }
    }
    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = viewModel::refresh,
        modifier = modifier,
    ) { content() }
}

/** "Atualizado há X min", recalculado a cada 30 s. */
@Composable
private fun UpdatedAgoText(updatedAt: Long?, offline: Boolean) {
    val now by produceState(System.currentTimeMillis(), updatedAt) {
        while (true) {
            value = System.currentTimeMillis()
            delay(30_000)
        }
    }
    val label = when {
        offline -> "Sem internet — mostrando resultados salvos" + (updatedAgoLabel(updatedAt, now)?.let { " · ${it.lowercase()}" } ?: "")
        else -> updatedAgoLabel(updatedAt, now)
    } ?: return
    Text(label, style = MaterialTheme.typography.labelSmall, color = if (offline) LicitaColors.Yellow else LicitaColors.TextMuted)
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
                if (state.offline) "Sem internet — busca indisponível até reconectar. A lista será atualizada automaticamente quando a conexão voltar." else error,
                title = when {
                    state.offline -> "Sem internet"
                    isConnectivityError(error) -> "Sem conexão com as fontes"
                    else -> "Não foi possível concluir"
                },
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
                    UpdatedAgoText(state.updatedAt, state.offline)
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

/**
 * Rotula a origem dos resultados. As fontes reais são as consultas públicas do PNCP e do Compras.gov.br;
 * Licitanet, BLL e Portal de Compras Públicas aparecem pelas publicações dessas plataformas no PNCP.
 */
@Suppress("UNUSED_PARAMETER")
internal fun sourceLabel(items: List<ScoredOpportunity>): String = "Fontes: PNCP e Compras.gov.br (consulta pública)"

/** Linha "via PNCP · <plataforma>" do card; null quando não há o que acrescentar ao chip do portal. */
internal fun platformLine(portal: Portal, platformName: String?, opportunityId: String): String? = when {
    portal == Portal.PNCP && !platformName.isNullOrBlank() -> "via PNCP · $platformName"
    portal != Portal.PNCP && opportunityId.startsWith("${Portal.PNCP.name}:") -> "via PNCP"
    else -> null
}

/** "Atualizado agora" / "Atualizado há X min" / "Atualizado há X h". */
internal fun updatedAgoLabel(updatedAt: Long?, now: Long): String? {
    if (updatedAt == null || updatedAt <= 0) return null
    val minutes = ((now - updatedAt).coerceAtLeast(0) / 60_000L)
    return when {
        minutes < 1 -> "Atualizado agora"
        minutes < 60 -> "Atualizado há $minutes min"
        else -> "Atualizado há ${minutes / 60} h"
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
                platformLine(op.portal, op.platformName, op.id)?.let { line ->
                    Text(
                        line, style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
                    )
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
