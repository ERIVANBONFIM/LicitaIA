package com.licitaia.feature.tender

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.licitaia.core.ui.components.AlertBanner
import com.licitaia.core.ui.components.BindingConfirmDialog
import com.licitaia.core.ui.components.ButtonRow
import com.licitaia.core.ui.components.ErrorState
import com.licitaia.core.ui.components.GradientCard
import com.licitaia.core.ui.components.IconBubble
import com.licitaia.core.ui.components.InfoRow
import com.licitaia.core.ui.components.LicitaCard
import com.licitaia.core.ui.components.LicitaScaffold
import com.licitaia.core.ui.components.PrimaryButton
import com.licitaia.core.ui.components.SecondaryButton
import com.licitaia.core.ui.components.SectionHeader
import com.licitaia.core.ui.components.SelectChip
import com.licitaia.core.ui.components.SimulationBadge
import com.licitaia.core.ui.components.SkeletonList
import com.licitaia.core.ui.components.StatusBadge
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.components.color
import com.licitaia.core.ui.components.tone
import com.licitaia.core.ui.nav.LocalAppNavigator
import com.licitaia.core.ui.nav.Routes
import com.licitaia.core.ui.theme.LicitaColors
import com.licitaia.domain.model.Proposal
import com.licitaia.domain.model.ProposalStatus
import com.licitaia.domain.util.Formatters
import java.io.File

@Composable
fun ProposalScreen(viewModel: ProposalViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val context = LocalContext.current
    var showReject by rememberSaveable { mutableStateOf(false) }
    var showBinding by rememberSaveable { mutableStateOf(false) }
    /** true = confirmação do atalho "Aprovar e liberar" (revisão + aprovação + liberação). */
    var fastTrack by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ProposalEvent.Message -> navigator.showMessage(event.text)
                is ProposalEvent.Navigate -> navigator.navigate(event.route)
                is ProposalEvent.Share -> if (!sharePdf(context, event.path, event.title)) navigator.showMessage("Nenhum app disponível para compartilhar o PDF.")
            }
        }
    }

    val tender = state.tender
    LicitaScaffold(title = "Proposta Comercial", subtitle = tender?.number, showBack = true) { padding ->
        when {
            state.loading -> SkeletonList(Modifier.padding(padding))
            state.notFound || tender == null -> ErrorState("Esta licitação não existe ou pertence a outra empresa.", Modifier.padding(padding), title = "Licitação não encontrada")
            else -> {
                val selected = state.selected
                val editing = state.busy == null
                LazyColumn(
                    Modifier.fillMaxSize().padding(padding).imePadding(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "header") {
                        GradientCard(Modifier.fillMaxWidth()) {
                            TenderHeadline(tender, state.analysis)
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(if (selected != null) "Versão ${selected.version} · ${selected.status.label}" else "Sem proposta", style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextSecondary)
                                    Text(
                                        Formatters.brl(if (state.dirty) state.draft.total else selected?.totalValue),
                                        style = MaterialTheme.typography.headlineSmall, color = LicitaColors.GreenBright, fontWeight = FontWeight.Bold,
                                    )
                                    if (state.dirty) Text("Total do rascunho em edição (não salvo)", style = MaterialTheme.typography.labelSmall, color = LicitaColors.Yellow)
                                }
                                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (selected != null) StatusBadge(selected.status.label, selected.status.tone(), pulsing = selected.status == ProposalStatus.EM_REVISAO)
                                }
                            }
                            state.analysis?.let { a ->
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Faixa sugerida pela IA: ${Formatters.brlCompact(a.priceRange.min)} – ${Formatters.brlCompact(a.priceRange.max)} (ideal ${Formatters.brlCompact(a.priceRange.suggested)})",
                                    style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextSecondary,
                                )
                            }
                        }
                    }
                    item(key = "busy") {
                        AnimatedVisibility(state.busy != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                            Column {
                                AlertBanner(state.busy.orEmpty(), "", Tone.INFO, pulsing = true)
                                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp), color = LicitaColors.Blue, trackColor = LicitaColors.Outline)
                            }
                        }
                    }

                    if (selected == null) {
                        item(key = "onboarding") {
                            LicitaCard(Modifier.fillMaxWidth()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconBubble(Icons.Outlined.AutoAwesome, LicitaColors.Blue)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text("Nenhuma proposta ainda", style = MaterialTheme.typography.titleMedium, color = LicitaColors.TextPrimary)
                                        Text(
                                            if (state.hasOfficialItems) {
                                                "O app lê os itens oficiais atuais do edital (nº, descrição, unidade, quantidade e valor estimado) e calcula o preço " +
                                                    (if (state.analysis != null) "pela faixa sugerida na análise" else "com um pequeno desconto sobre o estimado") +
                                                    ", nunca acima do estimado. A IA sugere prazo, validade e observações. Tudo pode ser editado."
                                            } else if (state.analysis == null) {
                                                "A IA usa a análise do edital para montar os itens. Você pode gerar agora ou começar em branco."
                                            } else {
                                                "A IA monta itens, quantidades e preços a partir da análise e da faixa sugerida. Tudo pode ser editado antes da revisão."
                                            },
                                            style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary,
                                        )
                                    }
                                }
                                Spacer(Modifier.height(14.dp))
                                PrimaryButton("Gerar proposta com IA", viewModel::generateWithAi, Modifier.fillMaxWidth(), enabled = state.canPrepare && editing, loading = state.busy?.startsWith("Gerando") == true, icon = Icons.Outlined.AutoAwesome)
                                Spacer(Modifier.height(8.dp))
                                SecondaryButton("Começar em branco", viewModel::startBlank, Modifier.fillMaxWidth(), enabled = state.canPrepare && editing, icon = Icons.Outlined.EditNote, tone = Tone.NEUTRAL)
                                RbacHint(state.canPrepare, state.roleLabel, "preparar propostas")
                            }
                        }
                    } else {
                        item(key = "versions-h") { SectionHeader("Versões · ${state.versions.size}") }
                        item(key = "versions") {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(state.versions, key = { it.id }) { v ->
                                    SelectChip(
                                        "v${v.version} · ${v.status.label}", v.id == selected.id,
                                        { if (editing) viewModel.select(v.id) }, color = v.status.tone().let { if (it == Tone.NEUTRAL) LicitaColors.Blue else it.color() },
                                    )
                                }
                            }
                        }
                        item(key = "compare") { VersionComparison(selected, state.previous) }
                        if (selected.status == ProposalStatus.REJEITADA) {
                            item(key = "rejected") {
                                AlertBanner("Proposta rejeitada", selected.rejectionReason?.takeIf { it.isNotBlank() } ?: "Sem motivo informado.", Tone.DANGER)
                            }
                        }

                        val editableStatus = selected.status == ProposalStatus.RASCUNHO || selected.status == ProposalStatus.REJEITADA
                        item(key = "items-h") {
                            SectionHeader("Itens · ${state.draft.items.size}", actionLabel = "Adicionar", onAction = if (editing && state.canPrepare) viewModel::addItem else null)
                        }
                        item(key = "official") {
                            LicitaCard(Modifier.fillMaxWidth()) {
                                val pending = state.draft.items.count { it.priceMissing }
                                Text(
                                    if (editableStatus) "Edite qualquer campo do rascunho; o total é recalculado na hora. Valores acima do estimado pelo órgão ficam em destaque."
                                    else "Versão ${selected.status.label.lowercase()}: para alterar, edite e salve como nova versão.",
                                    style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary,
                                )
                                if (pending > 0) {
                                    Spacer(Modifier.height(6.dp))
                                    Text("$pending item(ns) sem preço (orçamento sigiloso): defina antes de enviar.", style = MaterialTheme.typography.labelSmall, color = LicitaColors.Yellow)
                                }
                                Spacer(Modifier.height(10.dp))
                                ButtonRow {
                                    if (state.hasOfficialItems && editableStatus) {
                                        SecondaryButton(
                                            "Atualizar valores do edital", viewModel::refreshOfficialValues, Modifier.weight(1f),
                                            enabled = editing && state.canPrepare && !state.dirty, icon = Icons.Outlined.Refresh,
                                        )
                                    }
                                    SecondaryButton(
                                        "Nova versão pela IA", viewModel::generateWithAi, Modifier.weight(1f),
                                        enabled = editing && state.canPrepare && !state.dirty, icon = Icons.Outlined.AutoAwesome, tone = Tone.NEUTRAL,
                                    )
                                }
                            }
                        }
                        itemsIndexed(state.draft.items, key = { index, _ -> "item-$index" }) { index, item ->
                            ItemEditor(
                                index = index, item = item, enabled = editing && state.canPrepare, removable = state.draft.items.size > 1,
                                onChange = { transform -> viewModel.editItem(index, transform) },
                                onRemove = { viewModel.removeItem(index) },
                            )
                        }
                        item(key = "terms") {
                            LicitaCard(Modifier.fillMaxWidth()) {
                                Text("Prazos e observações", style = MaterialTheme.typography.titleMedium, color = LicitaColors.TextPrimary)
                                Spacer(Modifier.height(10.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedTextField(
                                        value = state.draft.deliveryDays, onValueChange = { v -> viewModel.editDraft { it.copy(deliveryDays = v.filter(Char::isDigit).take(4)) } },
                                        label = { Text("Prazo de entrega (dias)") }, singleLine = true, enabled = editing && state.canPrepare,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                                        shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(1f),
                                    )
                                    OutlinedTextField(
                                        value = state.draft.validityDays, onValueChange = { v -> viewModel.editDraft { it.copy(validityDays = v.filter(Char::isDigit).take(4)) } },
                                        label = { Text("Validade (dias)") }, singleLine = true, enabled = editing && state.canPrepare,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                                        shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(1f),
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = state.draft.notes, onValueChange = { v -> viewModel.editDraft { it.copy(notes = v) } },
                                    label = { Text("Notas internas (não saem no PDF)") }, placeholder = { Text("Pendências, conferências, lembretes da equipe…") },
                                    minLines = 3, maxLines = 6, enabled = editing && state.canPrepare,
                                    shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                        item(key = "validation") {
                            AnimatedVisibility(state.validation != null) {
                                AlertBanner("Revise a proposta", state.validation.orEmpty(), Tone.WARNING)
                            }
                        }
                        item(key = "save") {
                            AnimatedVisibility(state.dirty, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                                LicitaCard(Modifier.fillMaxWidth(), accent = LicitaColors.Yellow) {
                                    Text("Alterações não salvas", style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary)
                                    Text("Salve na própria versão (rascunho) ou crie uma nova versão para manter o histórico.", style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
                                    Spacer(Modifier.height(12.dp))
                                    ButtonRow {
                                        SecondaryButton("Descartar", viewModel::discardChanges, Modifier.weight(0.8f), enabled = editing, tone = Tone.NEUTRAL)
                                        if (selected.status == ProposalStatus.RASCUNHO) {
                                            SecondaryButton("Salvar rascunho", viewModel::saveDraft, Modifier.weight(1f), enabled = editing && state.canPrepare, icon = Icons.Outlined.Save)
                                        }
                                        PrimaryButton("Nova versão", viewModel::saveNewVersion, Modifier.weight(1f), enabled = editing && state.canPrepare, icon = Icons.Outlined.History)
                                    }
                                }
                            }
                        }

                        item(key = "workflow-h") { SectionHeader("Fluxo de aprovação") }
                        item(key = "workflow") {
                            WorkflowCard(
                                state = state, selected = selected, editing = editing,
                                onSubmitReview = viewModel::submitForReview, onApprove = viewModel::approve,
                                onReject = { showReject = true }, onPrepareSubmission = { fastTrack = false; showBinding = true },
                                onFastTrack = { fastTrack = true; showBinding = true },
                            )
                        }
                        item(key = "pdf-h") { SectionHeader("Documento") }
                        item(key = "pdf") {
                            val hasPdf = selected.pdfPath?.let { File(it).exists() } == true
                            LicitaCard(Modifier.fillMaxWidth()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconBubble(Icons.Outlined.PictureAsPdf, LicitaColors.Red)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text("PDF da proposta v${selected.version}", style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary)
                                        Text(
                                            if (hasPdf) "Gerado · ${selected.pdfPath?.substringAfterLast('/')}" else "Ainda não gerado para esta versão",
                                            style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                if (state.dirty) {
                                    Spacer(Modifier.height(8.dp))
                                    Text("Salve as alterações antes de gerar o PDF.", style = MaterialTheme.typography.labelSmall, color = LicitaColors.Yellow)
                                }
                                if (state.missingCompanyData.isNotEmpty()) {
                                    Spacer(Modifier.height(6.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            "Complete o cadastro da empresa para o PDF (falta: ${state.missingCompanyData.joinToString(" e ")}).",
                                            style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextSecondary, modifier = Modifier.weight(1f),
                                        )
                                        TextButton(onClick = { navigator.navigate(Routes.COMPANIES) }) {
                                            Text("Empresas", color = LicitaColors.BlueBright, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                                        }
                                    }
                                }
                                Spacer(Modifier.height(12.dp))
                                ButtonRow {
                                    PrimaryButton(
                                        if (hasPdf) "Ver PDF" else "Gerar PDF", { viewModel.generatePdf(openAfter = true) }, Modifier.weight(1f),
                                        enabled = editing && !state.dirty, icon = Icons.Outlined.PictureAsPdf, tone = if (hasPdf) Tone.INFO else Tone.SUCCESS,
                                    )
                                    SecondaryButton("Compartilhar", viewModel::sharePdf, Modifier.weight(1f), enabled = editing && !state.dirty, icon = Icons.Outlined.Share, tone = Tone.NEUTRAL)
                                }
                            }
                        }
                    }
                }

                if (showReject && selected != null) {
                    RejectDialog(
                        onDismiss = { showReject = false },
                        onConfirm = { reason -> showReject = false; viewModel.reject(reason) },
                    )
                }
                if (showBinding && selected != null) {
                    val numbers = selected.items.mapNotNull { it.itemNumber }
                    BindingConfirmDialog(
                        title = if (fastTrack) "Aprovar e liberar para o portal" else "Preparar envio ao portal",
                        details = listOf(
                            "Portal" to tender.portal.displayName,
                            "Licitação" to tender.number,
                            "Órgão" to tender.agency,
                            "Itens" to if (numbers.isNotEmpty()) "${selected.items.size} item(ns) · nº ${numbers.joinToString(limit = 12)}" else "${selected.items.size} item(ns)",
                            "Valor total" to Formatters.brl(selected.totalValue),
                            "Versão" to if (fastTrack) "v${selected.version} (${selected.status.label.lowercase()} → revisada e aprovada por ${state.userName.ifBlank { "você" }} agora)"
                            else "v${selected.version} (aprovada${selected.approvedBy?.let { " por $it" } ?: ""})",
                            "Empresa" to state.companyName,
                            "Usuário responsável" to state.userName,
                            "Data/hora" to Formatters.dateTime(System.currentTimeMillis()),
                        ),
                        acknowledgeText = (if (fastTrack) "Confirmo que revisei e aprovo esta proposta. " else "") +
                            "Confirmo que revisei os valores, os itens e a empresa, e autorizo cadastrá-la no portal (o robô preenche e salva item por item no Compras.gov.br depois da minha confirmação no plano do robô).",
                        confirmLabel = "Confirmar e soltar o robô",
                        onConfirm = {
                            showBinding = false
                            if (fastTrack) viewModel.approveAndRelease() else viewModel.simulateSubmission()
                        },
                        onDismiss = { showBinding = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun VersionComparison(selected: Proposal, previous: Proposal?) {
    LicitaCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(Icons.Outlined.History, LicitaColors.Purple, size = 34.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Versão ${selected.version}", style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary)
                Text(
                    "Por ${selected.createdBy} · ${Formatters.dateTime(selected.createdAt)}",
                    style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted,
                )
            }
            StatusBadge(selected.status.label, selected.status.tone())
        }
        Spacer(Modifier.height(8.dp))
        InfoRow("Total", Formatters.brl(selected.totalValue), valueColor = LicitaColors.GreenBright)
        InfoRow("Prazo de entrega", "${selected.deliveryDays} dias")
        InfoRow("Validade", "${selected.validityDays} dias")
        InfoRow("Itens", "${selected.items.size}")
        selected.approvedBy?.let { InfoRow("Aprovada por", "$it · ${Formatters.dateTime(selected.approvedAt)}") }
        if (previous != null) {
            Spacer(Modifier.height(6.dp))
            Text("Comparação com a v${previous.version}", style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextSecondary)
            val totalDiff = selected.totalValue - previous.totalValue
            val pct = if (previous.totalValue > 0) totalDiff / previous.totalValue * 100 else 0.0
            val deliveryDiff = selected.deliveryDays - previous.deliveryDays
            val itemsDiff = selected.items.size - previous.items.size
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusBadge(
                    when {
                        totalDiff == 0.0 -> "Total igual"
                        totalDiff < 0 -> "Total ${Formatters.brlCompact(-totalDiff)} menor (${Formatters.percent(-pct)})"
                        else -> "Total ${Formatters.brlCompact(totalDiff)} maior (${Formatters.percent(pct)})"
                    },
                    when {
                        totalDiff == 0.0 -> Tone.NEUTRAL
                        totalDiff < 0 -> Tone.SUCCESS
                        else -> Tone.WARNING
                    },
                )
            }
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusBadge(
                    when {
                        deliveryDiff == 0 -> "Mesmo prazo"
                        deliveryDiff < 0 -> "Prazo ${-deliveryDiff} dia(s) menor"
                        else -> "Prazo ${deliveryDiff} dia(s) maior"
                    },
                    if (deliveryDiff == 0) Tone.NEUTRAL else Tone.INFO,
                )
                if (itemsDiff != 0) StatusBadge(if (itemsDiff > 0) "+$itemsDiff item(ns)" else "$itemsDiff item(ns)", Tone.INFO)
            }
        } else {
            Text("Primeira versão — sem comparação.", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun ItemEditor(
    index: Int,
    item: ItemDraft,
    enabled: Boolean,
    removable: Boolean,
    onChange: ((ItemDraft) -> ItemDraft) -> Unit,
    onRemove: () -> Unit,
) {
    LicitaCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp), accent = if (item.priceMissing || item.aboveEstimate) LicitaColors.Yellow else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                item.itemNumber.trim().takeIf { it.isNotEmpty() }?.let { "Item $it do edital" } ?: "Item ${index + 1}",
                style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary, modifier = Modifier.weight(1f),
            )
            Text(Formatters.brl(item.total), style = MaterialTheme.typography.titleSmall, color = LicitaColors.GreenBright, fontWeight = FontWeight.Bold)
            if (removable) {
                IconButton(onClick = onRemove, enabled = enabled) { Icon(Icons.Outlined.DeleteOutline, contentDescription = "Remover item", tint = LicitaColors.TextMuted) }
            }
        }
        when {
            item.confidentialBudget && item.priceMissing ->
                Text("Orçamento sigiloso: definir o preço unitário.", style = MaterialTheme.typography.labelSmall, color = LicitaColors.Yellow)
            item.priceMissing -> Text("Preço a definir.", style = MaterialTheme.typography.labelSmall, color = LicitaColors.Yellow)
            item.aboveEstimate -> Text(
                "Acima do estimado pelo órgão (${Formatters.brl(item.estimatedUnitPrice)}).",
                style = MaterialTheme.typography.labelSmall, color = LicitaColors.Yellow,
            )
            item.estimatedUnitPrice != null -> Text(
                "Estimado pelo órgão: ${Formatters.brl(item.estimatedUnitPrice)} / ${item.unit.ifBlank { "un" }}",
                style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted,
            )
        }
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = item.description, onValueChange = { v -> onChange { it.copy(description = v) } },
            label = { Text("Descrição") }, minLines = 1, maxLines = 8, enabled = enabled,
            shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = item.itemNumber, onValueChange = { v -> onChange { it.copy(itemNumber = v.filter(Char::isDigit).take(5)) } },
                label = { Text("Nº item") }, singleLine = true, enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(0.7f),
            )
            OutlinedTextField(
                value = item.unit, onValueChange = { v -> onChange { it.copy(unit = v.take(24)) } },
                label = { Text("Un.") }, singleLine = true, enabled = enabled,
                shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = item.quantity, onValueChange = { v -> onChange { it.copy(quantity = v) } },
                label = { Text("Qtd.") }, singleLine = true, enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(0.8f),
            )
            OutlinedTextField(
                value = item.unitPrice, onValueChange = { v -> onChange { it.copy(unitPrice = v) } },
                label = { Text("Preço unit. (R$)") }, singleLine = true, enabled = enabled,
                placeholder = { if (item.confidentialBudget) Text("definir") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(1.3f),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = item.brand, onValueChange = { v -> onChange { it.copy(brand = v.take(80)) } },
                label = { Text("Marca") }, singleLine = true, enabled = enabled,
                shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = item.manufacturer, onValueChange = { v -> onChange { it.copy(manufacturer = v.take(80)) } },
                label = { Text("Fabricante") }, singleLine = true, enabled = enabled,
                shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = item.model, onValueChange = { v -> onChange { it.copy(model = v.take(120)) } },
            label = { Text("Modelo / versão") }, singleLine = true, enabled = enabled,
            shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun WorkflowCard(
    state: ProposalUiState,
    selected: Proposal,
    editing: Boolean,
    onSubmitReview: () -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onPrepareSubmission: () -> Unit,
    onFastTrack: () -> Unit,
) {
    val locked = !editing || state.dirty
    val pendingPrices = selected.pendingPriceItems.isNotEmpty()
    // Dono/Admin: um toque (com a confirmação explícita) em vez de revisão → aprovação → liberação.
    val fastTrack: @Composable () -> Unit = {
        if (state.canFastTrack) {
            Spacer(Modifier.height(8.dp))
            PrimaryButton(
                "Aprovar e liberar para o portal", onFastTrack, Modifier.fillMaxWidth(),
                enabled = !locked && !pendingPrices, icon = Icons.AutoMirrored.Outlined.Send, tone = Tone.WARNING,
            )
            Text(
                "Seu perfil (${state.roleLabel}) pode revisar, aprovar e liberar de uma vez. Você confirma os dados antes.",
                style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted, modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
    LicitaCard(Modifier.fillMaxWidth(), accent = selected.status.tone().takeIf { it != Tone.NEUTRAL }?.color()) {
        WorkflowSteps(selected.status)
        Spacer(Modifier.height(12.dp))
        if (state.dirty) {
            Text("Salve as alterações antes de avançar no fluxo.", style = MaterialTheme.typography.labelSmall, color = LicitaColors.Yellow)
            Spacer(Modifier.height(8.dp))
        }
        when (selected.status) {
            ProposalStatus.RASCUNHO, ProposalStatus.REJEITADA -> {
                Text(
                    if (selected.status == ProposalStatus.REJEITADA) "Ajuste a proposta e envie para nova revisão." else "Quando a proposta estiver pronta, envie para revisão da Diretoria.",
                    style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary,
                )
                Spacer(Modifier.height(10.dp))
                if (pendingPrices) {
                    Text("Defina o preço de todos os itens antes de enviar.", style = MaterialTheme.typography.labelSmall, color = LicitaColors.Yellow)
                    Spacer(Modifier.height(6.dp))
                }
                if (state.canFastTrack) {
                    SecondaryButton(
                        if (selected.status == ProposalStatus.REJEITADA) "Enviar para nova revisão" else "Enviar para revisão",
                        onSubmitReview, Modifier.fillMaxWidth(), enabled = !locked && !pendingPrices, icon = Icons.AutoMirrored.Outlined.Send, tone = Tone.NEUTRAL,
                    )
                    fastTrack()
                } else {
                    PrimaryButton(
                        if (selected.status == ProposalStatus.REJEITADA) "Enviar para nova revisão" else "Enviar para revisão",
                        onSubmitReview, Modifier.fillMaxWidth(), enabled = !locked && state.canPrepare && !pendingPrices, icon = Icons.AutoMirrored.Outlined.Send,
                    )
                }
                RbacHint(state.canPrepare, state.roleLabel, "enviar propostas para revisão")
            }
            ProposalStatus.EM_REVISAO -> {
                Text("Aguardando aprovação. Perfis Diretoria e Administrador podem aprovar ou rejeitar.", style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
                Spacer(Modifier.height(10.dp))
                ButtonRow {
                    SecondaryButton("Rejeitar", onReject, Modifier.weight(1f), enabled = !locked && state.canApprove, icon = Icons.Outlined.ThumbDown, tone = Tone.DANGER)
                    PrimaryButton("Aprovar", onApprove, Modifier.weight(1f), enabled = !locked && state.canApprove, icon = Icons.Outlined.ThumbUp, tone = Tone.SUCCESS)
                }
                fastTrack()
                RbacHint(state.canApprove, state.roleLabel, "aprovar propostas")
            }
            ProposalStatus.APROVADA -> {
                Text(
                    "Aprovada${selected.approvedBy?.let { " por $it" } ?: ""} em ${Formatters.dateTime(selected.approvedAt)}. O cadastro no portal é feito pelo robô após a sua confirmação final.",
                    style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary,
                )
                Spacer(Modifier.height(10.dp))
                PrimaryButton("Preparar envio ao portal", onPrepareSubmission, Modifier.fillMaxWidth(), enabled = !locked && state.canSubmit, icon = Icons.AutoMirrored.Outlined.Send, tone = Tone.WARNING)
                RbacHint(state.canSubmit, state.roleLabel, "aprovar o envio ao portal")
            }
            ProposalStatus.ENVIADA_SIMULADA -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusBadge("Liberada para o portal", Tone.SUCCESS)
                }
                Spacer(Modifier.height(6.dp))
                Text("Proposta liberada e registrada na auditoria. Acompanhe o cadastro no Comprasnet pelo Robô. Para alterar a proposta, crie uma nova versão.", style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
                Spacer(Modifier.height(10.dp))
                val navigator = LocalAppNavigator.current
                SecondaryButton("Abrir o robô desta licitação", { navigator.navigate("robotproposal/${selected.tenderId}") }, Modifier.fillMaxWidth(), enabled = editing, icon = Icons.AutoMirrored.Outlined.Send)
            }
        }
    }
}

@Composable
private fun WorkflowSteps(status: ProposalStatus) {
    val steps = listOf("Rascunho", "Revisão", "Aprovação", "Portal")
    val reached = when (status) {
        ProposalStatus.RASCUNHO -> 1
        ProposalStatus.EM_REVISAO -> 2
        ProposalStatus.REJEITADA -> 2
        ProposalStatus.APROVADA -> 3
        ProposalStatus.ENVIADA_SIMULADA -> 4
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        steps.forEachIndexed { i, label ->
            val done = i < reached
            val tone = when {
                status == ProposalStatus.REJEITADA && i == 1 -> Tone.DANGER
                done -> Tone.SUCCESS
                else -> Tone.NEUTRAL
            }
            StatusBadge(if (status == ProposalStatus.REJEITADA && i == 1) "Rejeitada" else label, tone, pulsing = i == reached - 1 && status == ProposalStatus.EM_REVISAO)
        }
    }
}

@Composable
private fun RbacHint(allowed: Boolean, roleLabel: String, action: String) {
    if (!allowed) {
        Text(
            "Seu perfil (${roleLabel.ifBlank { "—" }}) não pode $action.",
            style = MaterialTheme.typography.labelSmall, color = LicitaColors.Yellow, modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun RejectDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var reason by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.ThumbDown, contentDescription = null, tint = LicitaColors.Red) },
        title = { Text("Rejeitar proposta") },
        text = {
            Column {
                Text("Informe o motivo. Ele ficará registrado na auditoria e visível para quem elaborou a proposta.", color = LicitaColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = reason, onValueChange = { reason = it }, label = { Text("Motivo") },
                    minLines = 2, maxLines = 4, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(reason) }, enabled = reason.trim().length >= 5, colors = ButtonDefaults.buttonColors(containerColor = LicitaColors.Red)) { Text("Rejeitar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
        containerColor = LicitaColors.SurfaceElevated,
    )
}
