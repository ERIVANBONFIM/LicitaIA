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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import com.licitaia.domain.model.LowAdherence
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import com.licitaia.domain.model.Opportunity
import com.licitaia.domain.model.ProposalWindow
import com.licitaia.domain.model.ProposalWindows
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
import com.licitaia.core.ui.components.SelectChip
import com.licitaia.core.ui.components.OfficialSituationBadge
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.licitaia.domain.model.PeriodField
import com.licitaia.domain.model.PeriodFilter
import com.licitaia.domain.model.PeriodPreset
import com.licitaia.core.ui.components.SkeletonList
import com.licitaia.core.ui.components.StatusBadge
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.nav.LocalAppNavigator
import com.licitaia.core.ui.theme.LicitaColors
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.ScoreSource
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
 * Puxar para atualizar (consulta incremental leve às fontes) + re-filtro local pelo relógio enquanto a tela está
 * visível (STARTED), sem rede: as novas licitações chegam pela atualização diária (05:30). O laço é cancelado ao sair
 * da tela ou ir para segundo plano.
 *
 * O indicador grande (Material3) fica alinhado ao topo DESTA caixa (o topo da lista) e só existe enquanto o usuário
 * puxa ou durante a atualização pedida por ele ([OpportunityListState.userRefreshing]).
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
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.clockRefreshLoop() }
    }
    val pullState = rememberPullToRefreshState()
    val refreshing = state.userRefreshing
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = viewModel::refresh,
        modifier = modifier,
        state = pullState,
        indicator = {
            if (refreshing || pullState.distanceFraction > 0f) {
                PullToRefreshDefaults.Indicator(
                    state = pullState,
                    isRefreshing = refreshing,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        },
    ) { content() }
}

/** "Atualizado há X min" (+ indicador discreto durante a atualização automática), recalculado a cada 30 s. */
@Composable
private fun UpdatedAgoRow(updatedAt: Long?, offline: Boolean, backgroundRefresh: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        UpdatedAgoText(updatedAt, offline)
        if (backgroundRefresh) {
            Spacer(Modifier.width(6.dp))
            CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp, color = LicitaColors.TextMuted)
            Spacer(Modifier.width(4.dp))
            Text("atualizando…", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
        }
    }
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
    // Recalcula o texto só quando o minuto exibido muda (o produceState tica a cada 30 s).
    val minuteBucket = now / 60_000L
    val label = remember(updatedAt, offline, minuteBucket) {
        when {
            offline -> "Sem internet — mostrando resultados salvos" + (updatedAgoLabel(updatedAt, now)?.let { " · ${it.lowercase()}" } ?: "")
            else -> updatedAgoLabel(updatedAt, now)
        }
    } ?: return
    Text(label, style = MaterialTheme.typography.labelSmall, color = if (offline) LicitaColors.Yellow else LicitaColors.TextMuted)
}

/**
 * Chip "Mostrar dias anteriores (N)": marcado, a lista inclui a seção "Dias anteriores" (do mês atual, da mais recente
 * para a mais antiga). Com texto digitado na busca os anteriores já entram (o chip só informa).
 */
internal fun LazyListScope.previousDaysChip(state: OpportunityListState, viewModel: OpportunityListViewModel) {
    if (state.loading || state.error != null) return
    if (state.previousCount <= 0 && !state.showPreviousDays) return
    item(key = "previous-days", contentType = "chip") {
        Row(Modifier.padding(horizontal = 16.dp)) {
            SelectChip(
                when {
                    state.previousForced && !state.showPreviousDays -> "Pesquisa inclui dias anteriores (${state.previousCount})"
                    else -> "Mostrar dias anteriores (${state.previousCount})"
                },
                state.showPreviousDays,
                { viewModel.setShowPreviousDays(!state.showPreviousDays) },
            )
        }
    }
}

/**
 * Chips de marcas: "Só novas", "Descartadas (N)" e o filtro de período (Hoje · Próximos 7 dias · Próximos 30 dias ·
 * Personalizado), todos sem nova consulta. Combinam com o padrão "de hoje em diante" e "Mostrar dias anteriores".
 */
@OptIn(ExperimentalMaterial3Api::class)
internal fun LazyListScope.markChips(state: OpportunityListState, viewModel: OpportunityListViewModel) {
    if (state.loading || state.error != null) return
    item(key = "mark-chips", contentType = "chip") {
        var periodOpen by remember { mutableStateOf(false) }
        val period = state.marks.period
        androidx.compose.foundation.lazy.LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "period") {
                SelectChip(if (period.active) "Período: ${period.label()}" else "Período", period.active, { periodOpen = true })
            }
            item(key = "new") {
                SelectChip(
                    if (state.marks.onlyNew) "Só novas" else "Só novas (${state.marks.newIds.size})",
                    state.marks.onlyNew, { viewModel.setOnlyNew(!state.marks.onlyNew) },
                )
            }
            if (state.discardedCount > 0 || state.marks.showDiscarded) {
                item(key = "discarded") {
                    SelectChip("Descartadas (${state.discardedCount})", state.marks.showDiscarded, { viewModel.setShowDiscarded(!state.marks.showDiscarded) })
                }
            }
        }
        if (periodOpen) PeriodDialog(period, onDismiss = { periodOpen = false }) { viewModel.setPeriod(it); periodOpen = false }
    }
}

/** Escolha do período: presets, data comparada (proposta/publicação) e intervalo personalizado (DateRangePicker). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeriodDialog(current: PeriodFilter, onDismiss: () -> Unit, onApply: (PeriodFilter) -> Unit) {
    var preset by remember { mutableStateOf(current.preset) }
    var field by remember { mutableStateOf(current.field) }
    val range = rememberDateRangePickerState(
        initialSelectedStartDateMillis = current.customFrom?.let(::localToUtcMidnight),
        initialSelectedEndDateMillis = current.customTo?.let(::localToUtcMidnight),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Filtrar por período") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(PeriodPreset.entries, key = { it.name }) { p -> SelectChip(p.label, preset == p, { preset = p }) }
                }
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(PeriodField.entries, key = { it.name }) { f -> SelectChip("Data: ${f.label}", field == f, { field = f }) }
                }
                if (preset == PeriodPreset.CUSTOM) {
                    DateRangePicker(state = range, modifier = Modifier.height(420.dp), showModeToggle = true)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onApply(
                    PeriodFilter(
                        preset = preset, field = field,
                        customFrom = if (preset == PeriodPreset.CUSTOM) range.selectedStartDateMillis?.let(::utcToLocalMidnight) else null,
                        customTo = if (preset == PeriodPreset.CUSTOM) (range.selectedEndDateMillis ?: range.selectedStartDateMillis)?.let(::utcToLocalMidnight) else null,
                    ),
                )
            }) { Text("Aplicar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

/** Barra "Descartada · Desfazer" (some sozinha após alguns segundos). */
@Composable
internal fun UndoDiscardBar(state: OpportunityListState, viewModel: OpportunityListViewModel, modifier: Modifier = Modifier) {
    val item = state.lastDiscarded ?: return
    Surface(color = MaterialTheme.colorScheme.inverseSurface, shape = MaterialTheme.shapes.medium, modifier = modifier.fillMaxWidth().padding(16.dp)) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Descartada: ${item.opportunity.number}", color = MaterialTheme.colorScheme.inverseOnSurface,
                style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { viewModel.restore(item) }) { Text("Desfazer", color = MaterialTheme.colorScheme.inversePrimary) }
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
    /** Mostrar a linha da atualização diária no cabeçalho (a Busca já a mostra na linha de fontes). */
    showScheduleInHeader: Boolean = true,
) {
    val error = state.error
    // Varredura completa das fontes em andamento: progresso visível (nunca um vazio enganoso).
    state.syncLabel?.let { label ->
        item(key = "sync-progress", contentType = "note") {
            Text(
                if (state.partialSync && !state.loading) "$label — resultado parcial, a lista será atualizada ao terminar" else label,
                style = MaterialTheme.typography.labelSmall, color = LicitaColors.Yellow,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
    when {
        state.loading -> item(key = "loading", contentType = "loading") { SkeletonList(Modifier.padding(horizontal = 0.dp), items = 3) }
        error != null -> item(key = "error", contentType = "error") {
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
        state.items.isEmpty() -> {
            // Diagnóstico: mesmo sem resultados, mostra quanto cada fonte trouxe antes dos filtros.
            state.sourceSummary?.let { summary ->
                item(key = "sources-empty", contentType = "note") {
                    val reason = state.emptyReason
                    val low = LowAdherence.hiddenLabel(state.hiddenLow.size)
                    val window = state.windowSummary?.takeIf { state.previousCount > 0 }
                    Text(
                        remember(summary, reason, low, window) {
                            "Obtidos das fontes: $summary" + (window?.let { " · $it" } ?: "") + (low?.let { " · $it" } ?: "") +
                                (reason?.takeIf { low == null }?.let { " — $it" } ?: "")
                        },
                        style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
            item(key = "empty", contentType = "empty") { EmptyState(emptyTitle, emptyMessage, icon = emptyIcon) }
        }
        else -> {
            item(key = "count", contentType = "header") {
                // Textos de diagnóstico só são refeitos quando os dados mudam (não a cada recomposição).
                val countText = remember(state.items.size) { "${state.items.size} oportunidade(s) encontrada(s)" }
                val lowHidden = state.hiddenLow.size
                val sourcesText = remember(state.sourceSummary, lowHidden) {
                    val base = state.sourceSummary?.let { "Obtidos das fontes: $it" } ?: sourceLabel(state.items)
                    LowAdherence.hiddenLabel(lowHidden)?.let { "$base · $it" } ?: base
                }
                val aiText = remember(state.aiTotal, state.aiRated, state.aiFailure) { aiProgressLabel(state) }
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        countText,
                        style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextSecondary,
                    )
                    // "312 de hoje em diante · 45 de dias anteriores ocultos · 120 dispensas".
                    state.windowSummary?.let { line ->
                        Text(line, style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextSecondary)
                    }
                    Text(
                        sourcesText,
                        style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted,
                    )
                    val schedule = state.scheduleLine
                    if (state.fromSnapshot && schedule != null && !state.offline) {
                        // Lista do que está salvo: a linha da atualização diária substitui o "Atualizado há X min".
                        if (showScheduleInHeader) Text(schedule, style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
                    } else {
                        UpdatedAgoRow(state.updatedAt, state.offline, backgroundRefresh = state.refreshing && !state.userRefreshing)
                    }
                    aiText?.let { label ->
                        Text(
                            label, style = MaterialTheme.typography.labelSmall,
                            color = if (state.aiFailure != null) LicitaColors.Yellow else LicitaColors.TextMuted,
                        )
                    }
                }
            }
            // Seções fixas: Hoje → Próximos dias → Vão abrir, com cabeçalho fixo (stickyHeader).
            for (section in state.sections) {
                stickyHeader(key = "section-${section.section.name}", contentType = "section") {
                    SectionHeader(section.section.title, section.items.size)
                }
                items(section.items, key = { it.opportunity.id }, contentType = { "opportunity" }) { item ->
                    OpportunityCard(
                        item = item,
                        busy = item.opportunity.id in state.busy,
                        canAnalyze = state.canAnalyze,
                        aiActive = state.aiTotal > 0,
                        isNew = item.opportunity.id in state.marks.newIds,
                        onDiscard = { viewModel.discard(item) },
                        onRestore = if (state.marks.showDiscarded) ({ viewModel.restore(item) }) else null,
                        onInterest = { viewModel.onInterest(item) },
                        onAnalyze = { viewModel.onAnalyze(item) },
                        modifier = Modifier.padding(horizontal = 16.dp).animateItem(),
                    )
                }
            }
        }
    }
}

/** Cabeçalho fixo de seção (fundo opaco para não sobrepor os cards ao rolar). */
@Composable
private fun SectionHeader(title: String, count: Int) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth()) {
        Text(
            remember(title, count) { "$title · $count" },
            style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}

/**
 * Rotula a origem dos resultados. As fontes reais são as consultas públicas do PNCP e do Compras.gov.br;
 * Licitanet, BLL e Portal de Compras Públicas aparecem pelas publicações dessas plataformas no PNCP.
 */
@Suppress("UNUSED_PARAMETER")
internal fun sourceLabel(items: List<ScoredOpportunity>): String = "Fontes: PNCP e Compras.gov.br (consulta pública)"

/**
 * "Notas por IA: N de M" (atualiza conforme os lotes chegam); com falha, avisa que o restante ficou heurístico.
 * null quando não há nota por IA nesta lista.
 */
internal fun aiProgressLabel(state: OpportunityListState): String? = when {
    state.aiTotal <= 0 -> null
    state.aiFailure != null -> "Notas por IA: ${state.aiRated} de ${state.aiTotal} · ${state.aiFailure}"
    state.aiPending -> "Notas por IA: ${state.aiRated} de ${state.aiTotal} (avaliando…)"
    else -> "Notas por IA: ${state.aiRated} de ${state.aiTotal}"
}

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
    /** A lista usa nota por IA: notas heurísticas aparecem marcadas como tal. */
    aiActive: Boolean = false,
    /** Selo "Nova" (entrou no cache na última atualização / desde a última abertura e nunca foi aberta). */
    isNew: Boolean = false,
    /** "Descartar" (null = sem a ação nesta lista). */
    onDiscard: (() -> Unit)? = null,
    /** Lista de descartadas: o botão vira "Restaurar". */
    onRestore: (() -> Unit)? = null,
) {
    val op = item.opportunity
    // Formatações (moeda/datas/linha da plataforma) só quando a oportunidade muda.
    val minute = System.currentTimeMillis() / 60_000L
    val texts = remember(op, minute) { CardTexts.of(op, minute * 60_000L) }
    LicitaCard(modifier.fillMaxWidth(), accent = if (item.interested) LicitaColors.Green else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ScoreRing(item.score, size = 58.dp, strokeWidth = 5.dp)
                if (item.scoreSource == ScoreSource.AI) {
                    StatusBadge("IA", Tone.INFO, Modifier.padding(top = 4.dp))
                } else if (aiActive) {
                    Text("heurística", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted, modifier = Modifier.padding(top = 4.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PortalChip(op.portal)
                    StatusBadge(op.modality.label, Tone.NEUTRAL)
                    // Situação oficial à vista (vermelho/âmbar/cinza) ou ADIADA; depois o selo "Nova".
                    if (op.officialSituation != null) OfficialSituationBadge(op.officialSituation)
                    else if (texts.postponed != null) StatusBadge("ADIADA", Tone.WARNING)
                    if (isNew) StatusBadge("Nova", Tone.INFO)
                }
                texts.platform?.let { line ->
                    Text(
                        line, style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))
                // "48/2026 · UASG 160123" ("Cód. unidade" fora do Compras.gov.br).
                Text(texts.numberLine, style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(op.agency, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(op.objectDescription, style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (item.scoreSource == ScoreSource.AI && !item.scoreReason.isNullOrBlank()) {
            Text(
                "IA: ${item.scoreReason}", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextSecondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Valor estimado", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
                Text(texts.value, style = MaterialTheme.typography.titleMedium, color = LicitaColors.GreenBright, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Icon(Icons.Outlined.Place, contentDescription = null, tint = LicitaColors.TextMuted, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(texts.place, style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextSecondary, maxLines = 1)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            DateCell("Publicação", texts.published, Modifier.weight(1f))
            DateCell("Propostas até", texts.deadline, Modifier.weight(1.3f))
            DateCell("Sessão", texts.session, Modifier.weight(1.3f))
        }
        if (op.officialSituation != null || texts.postponed != null) {
            // Situação oficial/adiamento no lugar do "Aberta · encerra em…" (não induz a enviar proposta).
            Spacer(Modifier.height(6.dp))
            Text(
                op.officialSituation?.let { "Situação oficial: ${it.label}" } ?: texts.postponed.orEmpty(),
                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                color = when (op.officialSituation?.severity) {
                    com.licitaia.domain.model.SituationSeverity.RED -> LicitaColors.Red
                    com.licitaia.domain.model.SituationSeverity.GRAY -> LicitaColors.TextMuted
                    else -> LicitaColors.Yellow
                },
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            texts.postponedBefore?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted) }
        } else if (texts.status != null || texts.today || texts.closed || texts.previousDay) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    texts.closed -> StatusBadge("ENCERRADA", Tone.NEUTRAL)
                    texts.previousDay -> StatusBadge("DIA ANTERIOR", Tone.NEUTRAL)
                    texts.today -> StatusBadge("HOJE", Tone.WARNING)
                }
                texts.status?.let { status ->
                    Text(
                        status, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                        color = when {
                            texts.closed || texts.previousDay -> LicitaColors.TextMuted
                            texts.upcoming -> LicitaColors.TextSecondary
                            else -> LicitaColors.GreenBright
                        },
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
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
        if (onRestore != null) {
            TextButton(onClick = onRestore, modifier = Modifier.align(Alignment.End)) { Text("Restaurar na lista") }
        } else if (onDiscard != null) {
            TextButton(onClick = onDiscard, modifier = Modifier.align(Alignment.End)) {
                Text("Descartar", color = LicitaColors.TextSecondary)
            }
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

/** Textos formatados do card (calculados uma vez por oportunidade). */
internal class CardTexts(
    val platform: String?,
    val value: String,
    val place: String,
    val published: String,
    val deadline: String,
    val session: String,
    /** "Abre em dd/MM HH:mm" / "Aberta · encerra em dd/MM HH:mm". */
    val status: String? = null,
    val upcoming: Boolean = false,
    /** Encerramento/sessão hoje (destaque "HOJE"). */
    val today: Boolean = false,
    /** Prazo de propostas já passou (estado "encerrada"). */
    val closed: Boolean = false,
    /** Data de proposta em dia anterior do mês atual ("Dias anteriores"). */
    val previousDay: Boolean = false,
    /** "48/2026 · UASG 160123". */
    val numberLine: String = "",
    /** "ADIADA para 15/10 09:00" (null quando não foi adiada). */
    val postponed: String? = null,
    /** "antes: 08/10 10:00". */
    val postponedBefore: String? = null,
) {
    companion object {
        fun of(op: Opportunity, now: Long) = CardTexts(
            numberLine = listOfNotNull(op.number, com.licitaia.domain.model.UasgCode.label(op)).joinToString(" · "),
            postponed = com.licitaia.domain.model.Postponement.label(op),
            postponedBefore = com.licitaia.domain.model.Postponement.beforeLabel(op),
            status = ProposalWindows.statusLabel(op, now),
            upcoming = ProposalWindows.classify(op, now) == ProposalWindow.UPCOMING,
            today = ProposalWindows.endsToday(op, now),
            closed = ProposalWindows.isClosed(op, now),
            previousDay = ProposalWindows.isPreviousDay(op, now),
            platform = platformLine(op.portal, op.platformName, op.id),
            value = Formatters.brl(op.estimatedValue),
            place = "${op.city}/${op.uf}",
            published = Formatters.date(op.publishedAt),
            deadline = if (op.hasProposalDeadline) Formatters.dateTime(op.proposalDeadline) else "prazo não informado",
            session = if (op.sessionAt > 0L) Formatters.dateTime(op.sessionAt) else "—",
        )
    }
}

@Composable
private fun DateCell(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted, maxLines = 1)
        Text(value, style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
