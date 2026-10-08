package com.licitaia.feature.documents

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NoteAdd
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.licitaia.core.ui.components.AlertBanner
import com.licitaia.core.ui.components.EmptyState
import com.licitaia.core.ui.components.ErrorState
import com.licitaia.core.ui.components.IconBubble
import com.licitaia.core.ui.components.LicitaCard
import com.licitaia.core.ui.components.LicitaScaffold
import com.licitaia.core.ui.components.SectionHeader
import com.licitaia.core.ui.components.SelectChip
import com.licitaia.core.ui.components.SkeletonList
import com.licitaia.core.ui.components.StatusBadge
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.components.color
import com.licitaia.core.ui.components.tone
import com.licitaia.core.ui.nav.LocalAppNavigator
import com.licitaia.core.ui.nav.Routes
import com.licitaia.core.ui.theme.LicitaColors
import com.licitaia.domain.documents.DocumentValidity
import com.licitaia.domain.model.DocumentStatus
import com.licitaia.domain.model.DocumentType
import com.licitaia.domain.util.Formatters

/** documentId negativo especial: novo documento já com o tipo pré-selecionado. */
internal fun newDocumentIdFor(type: DocumentType): Long = -(100L + type.ordinal)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DocumentsScreen(viewModel: DocumentsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val noPermissionMsg = "O perfil ${state.roleLabel} não pode gerenciar documentos."

    LicitaScaffold(
        title = "Documentos",
        showBack = false,
        floatingActionButton = {
            if (!state.loading && !state.noSession && state.error == null) {
                ExtendedFloatingActionButton(
                    onClick = {
                        if (state.canEdit) navigator.navigate(Routes.documentEdit()) else navigator.showMessage(noPermissionMsg)
                    },
                    containerColor = if (state.canEdit) LicitaColors.Blue else LicitaColors.SurfaceHigh,
                    contentColor = if (state.canEdit) Color.White else LicitaColors.TextMuted,
                    icon = { Icon(if (state.canEdit) Icons.Outlined.Add else Icons.Outlined.Lock, contentDescription = null) },
                    text = { Text("Novo documento") },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> SkeletonList()
                state.noSession -> ErrorState("Sessão encerrada. Entre novamente para ver o cofre de documentos.")
                state.error != null -> ErrorState(state.error ?: "", onRetry = viewModel::retry)
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "summary") { SummaryRow(state, viewModel::setStatusFilter) }

                    if (!state.canEdit) {
                        item(key = "rbac") {
                            AlertBanner("Somente leitura", "$noPermissionMsg Solicite a um administrador.", Tone.WARNING)
                        }
                    }
                    if (state.expiredCount > 0) {
                        item(key = "expired") {
                            AlertBanner(
                                "${state.expiredCount} documento(s) vencido(s)",
                                "Documentos vencidos podem inabilitar a empresa. Renove antes da próxima sessão.",
                                Tone.DANGER,
                                actionLabel = "Ver",
                                onAction = { viewModel.setStatusFilter(DocumentStatus.VENCIDO) },
                            )
                        }
                    }

                    item(key = "filters") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                item { SelectChip("Todos", state.statusFilter == null, { viewModel.setStatusFilter(null) }) }
                                items(DocumentStatus.entries.toList()) { s ->
                                    SelectChip(s.label, state.statusFilter == s, { viewModel.setStatusFilter(s) }, color = s.tone().color())
                                }
                            }
                            if (state.typesInUse.size > 1) {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    item { SelectChip("Todos os tipos", state.typeFilter == null, { viewModel.setTypeFilter(null) }) }
                                    items(state.typesInUse) { t ->
                                        SelectChip(t.label, state.typeFilter == t, { viewModel.setTypeFilter(t) })
                                    }
                                }
                            }
                        }
                    }

                    if (state.all.isEmpty()) {
                        item(key = "empty") {
                            EmptyState(
                                "Cofre vazio",
                                "Cadastre contrato social, certidões, atestados e demais documentos de habilitação da empresa.",
                                icon = Icons.Outlined.FolderOff,
                                actionLabel = if (state.canEdit) "Cadastrar documento" else null,
                                onAction = { navigator.navigate(Routes.documentEdit()) },
                            )
                        }
                    } else if (state.visible.isEmpty()) {
                        item(key = "emptyFilter") {
                            EmptyState("Nada neste filtro", "Nenhum documento corresponde aos filtros selecionados.")
                        }
                    } else {
                        items(state.visible, key = { "doc-${it.document.id}" }) { row ->
                            DocumentCard(
                                row,
                                Modifier.animateItem(),
                                onClick = { navigator.navigate(Routes.documentEdit(row.document.id)) },
                            )
                        }
                    }

                    if (state.missingTypes.isNotEmpty()) {
                        item(key = "missingHeader") { SectionHeader("Tipos obrigatórios ausentes") }
                        item(key = "missing") { MissingTypesCard(state.missingTypes, state.canEdit) { navigator.navigate(Routes.documentEdit(newDocumentIdFor(it))) } }
                    }

                    if (state.canEdit) {
                        item(key = "ocr") {
                            LicitaCard(Modifier.fillMaxWidth(), onClick = { navigator.navigate(Routes.documentEdit()) }) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconBubble(Icons.Outlined.DocumentScanner, LicitaColors.Purple)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text("Leitura automática", style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary)
                                        Text(
                                            "Anexe o PDF, uma foto ou imagens da galeria: o LicitaPRO identifica o tipo e lê emissor, número, CNPJ e validade. Você confere antes de salvar.",
                                            style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary,
                                        )
                                    }
                                    StatusBadge("OCR", Tone.SUCCESS)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryRow(state: DocumentsUiState, onFilter: (DocumentStatus?) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SummaryTile("Válidos", state.validCount, Tone.SUCCESS, Modifier.weight(1f)) { onFilter(DocumentStatus.VALIDO) }
        SummaryTile("Vencendo", state.expiringCount, Tone.WARNING, Modifier.weight(1f)) { onFilter(DocumentStatus.VENCE_EM_BREVE) }
        SummaryTile("Vencidos", state.expiredCount, Tone.DANGER, Modifier.weight(1f)) { onFilter(DocumentStatus.VENCIDO) }
        SummaryTile("Ausentes", state.missingCount, Tone.NEUTRAL, Modifier.weight(1f)) { onFilter(DocumentStatus.AUSENTE) }
    }
}

@Composable
private fun SummaryTile(label: String, count: Int, tone: Tone, modifier: Modifier, onClick: () -> Unit) {
    LicitaCard(
        modifier = modifier,
        onClick = onClick,
        accent = if (count > 0 && tone != Tone.NEUTRAL) tone.color() else null,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
    ) {
        Text("$count", style = MaterialTheme.typography.headlineMedium, color = tone.color())
        Text(label, style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextSecondary, maxLines = 1)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DocumentCard(row: DocumentRow, modifier: Modifier, onClick: () -> Unit) {
    val doc = row.document
    val tone = row.status.tone()
    LicitaCard(
        modifier = modifier.fillMaxWidth().animateContentSize(),
        onClick = onClick,
        accent = if (row.status == DocumentStatus.VENCIDO || row.status == DocumentStatus.VENCE_EM_BREVE) tone.color() else null,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                doc.type.label.uppercase(), style = MaterialTheme.typography.labelSmall, color = LicitaColors.BlueBright,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            StatusBadge(DocumentValidity.badge(row.status, row.daysToExpire), tone, pulsing = row.status == DocumentStatus.VENCIDO)
        }
        Spacer(Modifier.height(6.dp))
        Text(doc.title.ifBlank { doc.type.label }, style = MaterialTheme.typography.titleMedium, color = LicitaColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (!doc.issuer.isNullOrBlank()) {
            Text("Emissor: ${doc.issuer}", style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (doc.expiresAt == null) "Sem validade definida" else "Validade: ${Formatters.date(doc.expiresAt)}",
                style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary, modifier = Modifier.weight(1f),
            )
            val expiry = expiryText(row.daysToExpire, doc.expiresAt)
            if (expiry != null) {
                Text(expiry, style = MaterialTheme.typography.labelMedium, color = tone.color(), fontWeight = FontWeight.SemiBold)
            }
        }
        if (doc.attachmentUri != null || doc.tags.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (doc.attachmentUri != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.AttachFile, contentDescription = null, tint = LicitaColors.GreenBright, modifier = Modifier.size(14.dp))
                        Text("Anexo", style = MaterialTheme.typography.labelSmall, color = LicitaColors.GreenBright)
                    }
                }
                doc.tags.take(6).forEach { StatusBadge("#$it", Tone.NEUTRAL) }
            }
        }
    }
}

internal fun expiryText(days: Long?, expiresAt: Long?): String? {
    if (days == null || expiresAt == null) return null
    val expired = expiresAt < System.currentTimeMillis()
    return when {
        expired && days == 0L -> "venceu hoje"
        expired -> "vencido há ${-days} dia(s)"
        days == 0L -> "vence hoje"
        days == 1L -> "vence amanhã"
        else -> "vence em $days dias"
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MissingTypesCard(types: List<DocumentType>, canEdit: Boolean, onAdd: (DocumentType) -> Unit) {
    LicitaCard(Modifier.fillMaxWidth(), accent = LicitaColors.Yellow) {
        Text(
            "Estes tipos costumam ser exigidos na habilitação e ainda não têm documento cadastrado" +
                if (canEdit) ". Toque para cadastrar." else ".",
            style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary,
        )
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            types.forEach { type ->
                if (canEdit) {
                    SelectChip("+ ${type.label}", selected = true, onClick = { onAdd(type) }, color = LicitaColors.Yellow)
                } else {
                    StatusBadge(type.label, Tone.WARNING)
                }
            }
        }
        if (canEdit) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.NoteAdd, contentDescription = null, tint = LicitaColors.TextMuted, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("${types.size} tipo(s) pendente(s)", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
            }
        }
    }
}
