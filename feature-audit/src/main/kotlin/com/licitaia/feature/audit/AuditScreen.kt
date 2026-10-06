package com.licitaia.feature.audit

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.ManageSearch
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.licitaia.core.ui.components.EmptyState
import com.licitaia.core.ui.components.ErrorState
import com.licitaia.core.ui.components.InfoRow
import com.licitaia.core.ui.components.LicitaCard
import com.licitaia.core.ui.components.LicitaScaffold
import com.licitaia.core.ui.components.SecondaryButton
import com.licitaia.core.ui.components.SelectChip
import com.licitaia.core.ui.components.SkeletonList
import com.licitaia.core.ui.components.StatusBadge
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.components.color
import com.licitaia.core.ui.components.tone
import com.licitaia.core.ui.nav.LocalAppNavigator
import com.licitaia.core.ui.theme.LicitaColors
import com.licitaia.domain.model.AuditAction
import com.licitaia.domain.model.AuditEvent
import com.licitaia.domain.model.AuditOrigin
import com.licitaia.domain.model.AuditResult
import com.licitaia.domain.util.Formatters

private fun AuditOrigin.tone(): Tone = when (this) {
    AuditOrigin.USUARIO -> Tone.INFO
    AuditOrigin.ROBO -> Tone.WARNING
    AuditOrigin.IA -> Tone.SUCCESS
    AuditOrigin.SISTEMA -> Tone.NEUTRAL
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuditScreen(viewModel: AuditViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val integrity by viewModel.integrity.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val context = LocalContext.current
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf<AuditEvent?>(null) }
    val filters = state.filters
    val canShow = !state.loading && state.allowed && !state.noSession && state.error == null

    LicitaScaffold(
        title = "Auditoria",
        showBack = false,
        actions = {
            if (canShow) {
                IconButton(onClick = { showFilters = !showFilters }) {
                    Icon(
                        Icons.Outlined.FilterList, contentDescription = "Filtros",
                        tint = if (filters.activeCount > 0 || showFilters) LicitaColors.Blue else LicitaColors.TextPrimary,
                    )
                }
                IconButton(onClick = {
                    if (state.events.isEmpty()) navigator.showMessage("Não há eventos para exportar")
                    else shareText(
                        context,
                        buildExportText(state.events, if (filters.allCompanies) "Todas as empresas" else state.companyName),
                    ) { navigator.showMessage(it) }
                }) { Icon(Icons.Outlined.Share, contentDescription = "Exportar") }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> SkeletonList()
                state.noSession -> ErrorState("Sessão encerrada. Entre novamente para consultar a auditoria.")
                !state.allowed -> EmptyState(
                    "Acesso restrito",
                    "O perfil ${state.roleLabel} não possui a permissão \"Ver auditoria\". " +
                        "A trilha é visível para Administrador e Diretoria. Suas ações continuam sendo registradas.",
                    icon = Icons.Outlined.Lock,
                )
                state.error != null -> ErrorState(state.error ?: "", onRetry = viewModel::retry)
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                ) {
                    item(key = "scope") {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SelectChip("Empresa ativa", !filters.allCompanies, { viewModel.setAllCompanies(false) })
                            SelectChip("Todas as empresas", filters.allCompanies, { viewModel.setAllCompanies(true) })
                            Spacer(Modifier.weight(1f))
                            Text(
                                if (state.events.size == state.total) "${state.total} evento(s)" else "${state.events.size} de ${state.total}",
                                style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextSecondary,
                            )
                        }
                    }
                    item(key = "integrity") {
                        Spacer(Modifier.height(10.dp))
                        IntegrityPanel(integrity, onVerify = viewModel::verifyIntegrity)
                    }
                    item(key = "filters") {
                        AnimatedVisibility(showFilters || filters.activeCount > 0) {
                            FiltersPanel(filters, viewModel)
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    if (state.events.isEmpty()) {
                        item(key = "empty") {
                            if (state.total == 0) {
                                EmptyState(
                                    "Nenhum evento registrado",
                                    "Logins, análises, aprovações, lances, CAPTCHAs e envios aparecerão aqui em ordem cronológica.",
                                    icon = Icons.Outlined.ManageSearch,
                                )
                            } else {
                                EmptyState(
                                    "Nenhum evento neste filtro", "Ajuste ou limpe os filtros para ver mais eventos.",
                                    icon = Icons.Outlined.ManageSearch, actionLabel = "Limpar filtros", onAction = viewModel::clearFilters,
                                )
                            }
                        }
                    } else {
                        items(state.events.size, key = { i -> "ev-${state.events[i].id}-$i" }) { i ->
                            val event = state.events[i]
                            TimelineItem(
                                event = event,
                                showCompany = filters.allCompanies,
                                isLast = i == state.events.lastIndex,
                                modifier = Modifier.animateItem(),
                                onClick = { selected = event },
                            )
                        }
                    }
                }
            }
        }
    }

    selected?.let { event ->
        ModalBottomSheet(
            onDismissRequest = { selected = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = LicitaColors.SurfaceElevated,
        ) {
            EventDetail(event) { shareText(context, "LicitaIA — Evento de auditoria\n" + event.toExportLine()) { navigator.showMessage(it) } }
        }
    }
}

/** Verificação da cadeia de hashes: botão + resultado (ok / quebra a partir do evento N). */
@Composable
private fun IntegrityPanel(integrity: IntegrityUi, onVerify: () -> Unit) {
    val checking = integrity is IntegrityUi.Checking
    LicitaCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Integridade da trilha", style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary)
                Text(
                    "Cada evento guarda o hash SHA-256 do anterior; alterar ou remover um evento quebra a cadeia.",
                    style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary,
                )
            }
            Spacer(Modifier.width(10.dp))
            SecondaryButton(
                if (checking) "Verificando…" else "Verificar integridade", onVerify,
                enabled = !checking, icon = Icons.Outlined.VerifiedUser, tone = Tone.INFO,
            )
        }
        when (integrity) {
            IntegrityUi.Idle, IntegrityUi.Checking -> Unit
            is IntegrityUi.Failed -> {
                Spacer(Modifier.height(8.dp))
                Text(integrity.message, style = MaterialTheme.typography.bodySmall, color = LicitaColors.RedBright)
            }
            is IntegrityUi.Done -> {
                Spacer(Modifier.height(8.dp))
                val report = integrity.report
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusBadge(
                        if (report.ok) "Íntegra" else "Quebra no evento #${report.firstBroken}",
                        if (report.ok) Tone.SUCCESS else Tone.DANGER,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        report.summary(), style = MaterialTheme.typography.bodySmall,
                        color = if (report.ok) LicitaColors.TextSecondary else LicitaColors.RedBright, modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun FiltersPanel(filters: AuditFilters, viewModel: AuditViewModel) {
    // Estado local do campo evita saltos de cursor (o filtro no ViewModel é assíncrono).
    var query by rememberSaveable(filters.clears) { mutableStateOf(filters.query) }
    LicitaCard(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it.take(80); viewModel.setQuery(it) },
            placeholder = { Text("Buscar por usuário, pregão, item, motivo…") },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = ""; viewModel.setQuery("") }) { Icon(Icons.Outlined.Close, contentDescription = "Limpar busca") }
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        FilterLabel("Ação")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { SelectChip("Todas", filters.action == null, { viewModel.setAction(null) }) }
            items(AuditAction.entries.toList()) { a -> SelectChip(a.label, filters.action == a, { viewModel.setAction(a) }) }
        }
        FilterLabel("Origem")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { SelectChip("Todas", filters.origin == null, { viewModel.setOrigin(null) }) }
            items(AuditOrigin.entries.toList()) { o ->
                SelectChip(o.label, filters.origin == o, { viewModel.setOrigin(o) }, color = o.tone().color().takeIf { o.tone() != Tone.NEUTRAL } ?: LicitaColors.Blue)
            }
        }
        FilterLabel("Resultado")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { SelectChip("Todos", filters.result == null, { viewModel.setResult(null) }) }
            items(AuditResult.entries.toList()) { r ->
                SelectChip(r.label, filters.result == r, { viewModel.setResult(r) }, color = r.tone().color())
            }
        }
        if (filters.activeCount > 0) {
            Spacer(Modifier.height(10.dp))
            SecondaryButton("Limpar filtros (${filters.activeCount})", viewModel::clearFilters, tone = Tone.NEUTRAL, icon = Icons.Outlined.Close)
        }
    }
}

@Composable
private fun FilterLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextSecondary, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimelineItem(event: AuditEvent, showCompany: Boolean, isLast: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val color = event.result.tone().color()
    Row(
        modifier
            .fillMaxWidth()
            .drawBehind {
                // Trilho da timeline: linha vertical + marcador colorido pelo resultado.
                val x = 7.dp.toPx()
                val dotY = 22.dp.toPx()
                drawLine(LicitaColors.Outline, Offset(x, 0f), Offset(x, if (isLast) dotY else size.height), strokeWidth = 2.dp.toPx())
                drawCircle(LicitaColors.Background, radius = 7.dp.toPx(), center = Offset(x, dotY))
                drawCircle(color, radius = 5.dp.toPx(), center = Offset(x, dotY))
            }
            .padding(start = 24.dp, bottom = 10.dp),
    ) {
        LicitaCard(
            Modifier.fillMaxWidth(), onClick = onClick,
            accent = if (event.result == AuditResult.FALHA || event.result == AuditResult.BLOQUEADO) color else null,
            contentPadding = PaddingValues(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(event.action.label, style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(Formatters.dateTime(event.timestamp), style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
            }
            Text(
                event.user + if (showCompany && event.companyName.isNotBlank()) " · ${event.companyName}" else "",
                style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val context = listOfNotNull(event.portal, event.tenderNumber?.let { "Pregão $it" }, event.item).joinToString(" · ")
            if (context.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(context, style = MaterialTheme.typography.bodySmall, color = LicitaColors.BlueBright, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (event.previousValue != null || event.newValue != null) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(event.previousValue ?: "—", style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Text("  →  ", style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextMuted)
                    Text(event.newValue ?: "—", style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                }
            }
            if (!event.reason.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text("Motivo: ${event.reason}", style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusBadge(event.origin.label, event.origin.tone())
                StatusBadge(event.result.label, event.result.tone())
            }
        }
    }
}

@Composable
private fun EventDetail(event: AuditEvent, onShare: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(event.action.label, style = MaterialTheme.typography.headlineSmall, color = LicitaColors.TextPrimary, modifier = Modifier.weight(1f))
            StatusBadge(event.result.label, event.result.tone())
        }
        Spacer(Modifier.height(4.dp))
        Text("${Formatters.dateTime(event.timestamp)} · ${Formatters.relative(event.timestamp)}", style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
        Spacer(Modifier.height(12.dp))
        InfoRow("Usuário", event.user)
        InfoRow("Empresa", event.companyName.ifBlank { "—" })
        InfoRow("Portal", event.portal ?: "—")
        InfoRow("Pregão", event.tenderNumber ?: "—")
        InfoRow("Item", event.item ?: "—")
        InfoRow("Valor anterior", event.previousValue ?: "—")
        InfoRow("Valor novo", event.newValue ?: "—")
        InfoRow("Motivo", event.reason ?: "—")
        InfoRow("Origem", event.origin.label, valueColor = event.origin.tone().color())
        InfoRow("Resultado", event.result.label, valueColor = event.result.tone().color())
        if (event.details.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text("Detalhes", style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextSecondary)
            Text(event.details, style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextPrimary)
        }
        Spacer(Modifier.height(8.dp))
        Text("Hash (SHA-256)", style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextSecondary)
        Text(
            if (event.hash.isEmpty()) "— (evento anterior ao encadeamento)" else event.hash,
            style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextMuted,
        )
        Spacer(Modifier.height(16.dp))
        SecondaryButton("Compartilhar evento", onShare, Modifier.fillMaxWidth(), icon = Icons.Outlined.Share)
    }
}

private fun shareText(context: Context, text: String, onError: (String) -> Unit) {
    try {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "LicitaIA — Auditoria")
            // Limite conservador para não estourar o tamanho da transação Binder.
            .putExtra(Intent.EXTRA_TEXT, text.take(120_000))
        context.startActivity(Intent.createChooser(send, "Exportar auditoria").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        onError("Não foi possível abrir o compartilhamento")
    }
}
