package com.licitaia.feature.bidding.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.licitaia.core.ui.components.AlertBanner
import com.licitaia.core.ui.components.LicitaCard
import com.licitaia.core.ui.components.PrimaryButton
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.theme.LicitaColors
import com.licitaia.domain.model.PortalDeclarations
import com.licitaia.domain.portal.PortalMyTender
import com.licitaia.domain.portal.ProposalItemPlan
import com.licitaia.domain.util.Formatters
import com.licitaia.feature.live.automation.RobotPlanRules
import com.licitaia.feature.live.automation.TextNorm

/** Texto do checkbox obrigatório da autorização (vai para a auditoria). */
internal const val TERMS_AUTHORIZATION_TEXT = "Autorizo o aceite do Termo de Aceitação e das declarações obrigatórias apresentadas pelo Compras.gov"

/**
 * Confirmação "Soltar o robô — cadastrar proposta" em TELA CHEIA rolável: cabeçalho da compra, lista COMPLETA dos itens
 * com participação por item (marcar/desmarcar todos, contador, itens sem preço destacados e desmarcados), total dos
 * selecionados e "Termo e declarações" (autorização obrigatória + respostas da empresa, escolhidas aqui se faltarem).
 */
@Composable
internal fun ProposalConfirmDialog(
    tender: PortalMyTender,
    planItems: List<ProposalItemPlan>,
    companyDeclarations: PortalDeclarations,
    onEditCompany: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (items: List<ProposalItemPlan>, declarations: PortalDeclarations, saveDeclarations: Boolean) -> Unit,
) {
    val items = remember(planItems) { mutableStateListOf<ProposalItemPlan>().apply { addAll(planItems.sortedBy { it.itemNumber }.map { it.copy(selected = it.selected && it.hasPrice) }) } }
    var accept by remember { mutableStateOf(false) }
    var decl by remember(companyDeclarations) { mutableStateOf(companyDeclarations) }
    val sel = RobotPlanRules.selection(items)
    val errors = RobotPlanRules.confirmationErrors(items, accept, decl)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(LicitaColors.Background).statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.SmartToy, contentDescription = null, tint = LicitaColors.Yellow)
                Spacer(Modifier.width(8.dp))
                Text("Soltar robô — cadastrar proposta?", style = MaterialTheme.typography.titleMedium, color = LicitaColors.TextPrimary, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = "Fechar") }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    LicitaCard(Modifier.fillMaxWidth()) {
                        Text(listOf(tender.modality, "${tender.number}/${tender.year}").filter { it.isNotBlank() }.joinToString(" "), style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary)
                        Text("UASG ${tender.uasg.trimStart('0')}", style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
                        if (tender.objectDescription.isNotBlank()) Text(tender.objectDescription, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary, maxLines = 3)
                        val prazo = tender.openingAt?.let { "Prazo/sessão: ${Formatters.dateTime(it)}" } ?: tender.situation.takeIf { it.isNotBlank() }
                        prazo?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextMuted) }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "O robô vai localizar a compra, conferir que é a compra certa (UASG e número), verificar o prazo, aceitar os termos, " +
                                "abrir os grupos, preencher, salvar e reler cada item selecionado.",
                            style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted,
                        )
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(sel.label, style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary, modifier = Modifier.weight(1f))
                        TextButton(onClick = { val all = RobotPlanRules.selectAll(items, true); items.clear(); items.addAll(all) }) { Text("Marcar todos") }
                        TextButton(onClick = { val none = RobotPlanRules.selectAll(items, false); items.clear(); items.addAll(none) }) { Text("Desmarcar") }
                    }
                    if (sel.withoutPrice.isNotEmpty()) {
                        Text(
                            "${sel.withoutPrice.size} item(ns) sem preço ficam de fora (destacados): ${sel.withoutPrice.take(15).joinToString()}${if (sel.withoutPrice.size > 15) "…" else ""}. Defina o valor no plano para incluir.",
                            style = MaterialTheme.typography.labelSmall, color = LicitaColors.Yellow,
                        )
                    }
                }
                items(items, key = { it.itemNumber }) { i ->
                    val index = items.indexOfFirst { it.itemNumber == i.itemNumber }
                    ItemRow(i) { checked -> if (index >= 0 && (i.hasPrice || !checked)) items[index] = i.copy(selected = checked) }
                }
                item {
                    HorizontalDivider(color = LicitaColors.Outline)
                    Spacer(Modifier.height(8.dp))
                    Text("Termo e declarações", style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary)
                    Text(
                        "O robô marca o Termo de Aceitação e, quando o portal abre o modal de declarações, usa “Marcar todas”, confere uma a uma e só então toca em Confirmar. " +
                            "Se alguma ficar desmarcada, ele para sem confirmar. Depois aplica as respostas da empresa abaixo.",
                        style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted,
                    )
                    Spacer(Modifier.height(6.dp))
                    DeclarationLine("Declaração ME/EPP e equiparados", decl.meEpp) { decl = decl.copy(meEpp = it) }
                    DeclarationLine("Equidade entre mulheres e homens (art. 60, III)", decl.genderEquity) { decl = decl.copy(genderEquity = it) }
                    DeclarationLine("Programa de integridade (art. 60, IV)", decl.integrity) { decl = decl.copy(integrity = it) }
                    Text(
                        if (decl != companyDeclarations) "As respostas escolhidas aqui serão SALVAS no cadastro da empresa ao soltar o robô." else "Respostas do cadastro da empresa.",
                        style = MaterialTheme.typography.labelSmall, color = if (decl != companyDeclarations) LicitaColors.Yellow else LicitaColors.TextMuted,
                    )
                    TextButton(onClick = onEditCompany) { Text("Editar no cadastro da empresa") }
                    Row(Modifier.fillMaxWidth().clickable { accept = !accept }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = accept, onCheckedChange = { accept = it })
                        Text(TERMS_AUTHORIZATION_TEXT, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Column(Modifier.fillMaxWidth().background(LicitaColors.Surface).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Total ofertado (${sel.label}): ${Formatters.brl(sel.selectedTotal)}", style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary)
                errors.firstOrNull()?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = LicitaColors.Yellow) }
                PrimaryButton(
                    "Soltar o robô",
                    { onConfirm(items.toList(), decl, decl != companyDeclarations) },
                    Modifier.fillMaxWidth(), enabled = errors.isEmpty(), icon = Icons.Outlined.SmartToy, tone = Tone.WARNING,
                )
            }
        }
    }
}

@Composable
private fun ItemRow(i: ProposalItemPlan, onCheck: (Boolean) -> Unit) {
    val noPrice = !i.hasPrice
    LicitaCard(Modifier.fillMaxWidth(), accent = if (noPrice) LicitaColors.Yellow else null, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = i.selected, onCheckedChange = onCheck, enabled = !noPrice)
            Column(Modifier.weight(1f)) {
                Text(
                    if (noPrice) "Item ${i.itemNumber}: SEM PREÇO — fica de fora"
                    else "Item ${i.itemNumber}: ${Formatters.brl(i.unitPrice)} × ${TextNorm.formatInputNumber(i.quantity)} = ${Formatters.brl(i.totalPrice)}",
                    style = MaterialTheme.typography.bodySmall, color = if (noPrice) LicitaColors.Yellow else LicitaColors.TextPrimary,
                )
                if (i.description.isNotBlank()) Text(i.description, style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted, maxLines = 1)
                listOf("Marca" to i.brand, "Modelo" to i.modelVersion).filter { it.second.isNotBlank() }.takeIf { it.isNotEmpty() }?.let { l ->
                    Text(l.joinToString(" · ") { (k, v) -> "$k: $v" }, style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun DeclarationLine(label: String, value: Boolean?, onChange: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label + if (value == null) " — escolha" else "",
            style = MaterialTheme.typography.bodySmall, color = if (value == null) LicitaColors.Yellow else LicitaColors.TextPrimary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = value == true, onClick = { onChange(true) }, label = { Text("Sim") })
            FilterChip(selected = value == false, onClick = { onChange(false) }, label = { Text("Não") })
        }
    }
}

/** Banner usado no plano quando as declarações da empresa ainda não foram respondidas. */
@Composable
internal fun DeclarationsHint(d: PortalDeclarations, onEdit: () -> Unit) {
    if (d.complete) return
    AlertBanner(
        "Declarações da empresa pendentes",
        "Responda ME/EPP, equidade de gênero e programa de integridade (no cadastro da empresa ou na confirmação do robô).",
        Tone.WARNING, actionLabel = "Editar", onAction = onEdit,
    )
}
