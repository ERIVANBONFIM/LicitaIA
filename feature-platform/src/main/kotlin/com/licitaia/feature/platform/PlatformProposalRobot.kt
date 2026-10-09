package com.licitaia.feature.platform

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.licitaia.core.ui.components.IconBubble
import com.licitaia.core.ui.components.LicitaCard
import com.licitaia.core.ui.components.PrimaryButton
import com.licitaia.core.ui.components.SecondaryButton
import com.licitaia.core.ui.components.StatusBadge
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.theme.LicitaColors
import com.licitaia.domain.model.PortalDeclarations
import com.licitaia.domain.portal.ProposalItemPlan
import com.licitaia.domain.util.Formatters
import com.licitaia.feature.live.automation.RobotPlanRules
import com.licitaia.feature.live.automation.RobotRun
import com.licitaia.feature.live.automation.RunStatus

/** Mesmo texto da autorização do modo local (vai para a auditoria). */
private const val TERMO_AUTORIZACAO = "Autorizo o aceite do Termo de Aceitação e das declarações obrigatórias apresentadas pelo Compras.gov"

/**
 * Card "Robô de proposta" do detalhe da plataforma: o MESMO robô do modo local (no aparelho, certificado local)
 * abre o Compras.gov.br, acha a compra pela UASG + número, confere, preenche e salva os itens. O andamento aparece
 * aqui e na tela do portal ("Ver no portal"), onde o usuário assiste o robô trabalhando.
 */
@Composable
internal fun PropostaRoboCard(
    s: PlatformDetailUi,
    run: RobotRun?,
    onCadastrar: () -> Unit,
    onParar: () -> Unit,
    onVerNoPortal: () -> Unit,
) {
    val ativo = run?.active == true
    val accent = when {
        ativo -> LicitaColors.Yellow
        run?.status == RunStatus.CONCLUIDO -> LicitaColors.Green
        run?.status == RunStatus.FALHOU -> LicitaColors.Red
        else -> null
    }
    LicitaCard(Modifier.fillMaxWidth(), accent = accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(Icons.Outlined.SmartToy, accent ?: LicitaColors.Blue)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Robô de proposta", style = MaterialTheme.typography.titleMedium, color = LicitaColors.TextPrimary)
                Text("Cadastra a proposta no Comprasnet · no aparelho", style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
            }
            run?.let {
                StatusBadge(
                    it.status.label,
                    when (it.status) {
                        RunStatus.CONCLUIDO -> Tone.SUCCESS
                        RunStatus.FALHOU -> Tone.DANGER
                        RunStatus.EXECUTANDO -> Tone.INFO
                        else -> Tone.WARNING
                    },
                    pulsing = it.status == RunStatus.EXECUTANDO,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        if (run == null) {
            Text(
                "O robô abre o Compras.gov.br, procura a compra pela UASG e pelo número, confere que é a compra certa, " +
                    "aceita o termo com a sua autorização e preenche e salva os itens que você selecionar. " +
                    "Você assiste tudo na tela do portal. Precisa da sessão do Comprasnet logada em Portais.",
                style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextMuted,
            )
        } else {
            Text(run.step, style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextPrimary)
            run.message?.takeIf { it.isNotBlank() && it != run.step }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = if (run.needsUser) LicitaColors.Yellow else LicitaColors.TextSecondary)
            }
            run.log.takeLast(6).forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted) }
        }
        Spacer(Modifier.height(10.dp))
        if (ativo) {
            SecondaryButton("Ver o robô no portal", onVerNoPortal, Modifier.fillMaxWidth(), icon = Icons.Outlined.Language, tone = Tone.INFO)
            Spacer(Modifier.height(6.dp))
            SecondaryButton("Parar robô de proposta", onParar, Modifier.fillMaxWidth(), icon = Icons.Outlined.StopCircle, tone = Tone.DANGER)
        } else {
            PrimaryButton(
                "Robô cadastra proposta no portal", onCadastrar, Modifier.fillMaxWidth(),
                enabled = !s.propostaRoboBusy && s.itens.any { it.numero != null }, loading = s.propostaRoboBusy,
                icon = Icons.Outlined.SmartToy, tone = Tone.WARNING,
            )
            if (s.itens.none { it.numero != null }) {
                Text("Sem itens carregados nesta licitação.", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
            }
            Spacer(Modifier.height(6.dp))
            SecondaryButton("Abrir o portal (Comprasnet)", onVerNoPortal, Modifier.fillMaxWidth(), icon = Icons.Outlined.Language, tone = Tone.NEUTRAL)
        }
    }
}

/**
 * Confirmação "Soltar robô — cadastrar proposta" (tela cheia, no formato da confirmação do modo local): itens com
 * preço editável e participação por item, total, declarações da empresa e a autorização OBRIGATÓRIA do termo.
 */
@Composable
internal fun PropostaRoboDialog(
    titulo: String,
    subtitulo: String,
    candidatos: List<ProposalItemPlan>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (items: List<ProposalItemPlan>, declarations: PortalDeclarations, updateDifferent: Boolean) -> Unit,
) {
    val items = remember(candidatos) { mutableStateListOf<ProposalItemPlan>().apply { addAll(candidatos) } }
    // Valor de cada item em MÁSCARA DE DINHEIRO (como app de banco): só dígitos, os 2 últimos são os centavos
    // → "1400000" vira "14.000,00". O cursor fica sempre no fim.
    val precos = remember(candidatos) {
        mutableStateMapOf<Int, TextFieldValue>().apply {
            candidatos.forEach { val t = if (it.unitPrice > 0) fmtPreco(it.unitPrice) else ""; put(it.itemNumber, TextFieldValue(t, TextRange(t.length))) }
        }
    }
    var accept by remember { mutableStateOf(false) }
    var decl by remember { mutableStateOf(PortalDeclarations()) }
    var updateDifferent by remember { mutableStateOf(false) }
    val sel = RobotPlanRules.selection(items)
    val errors = RobotPlanRules.confirmationErrors(items, accept, decl)

    fun setPreco(n: Int, input: TextFieldValue) {
        val centavos = input.text.filter(Char::isDigit).trimStart('0').take(13).toLongOrNull() ?: 0L
        val v = centavos / 100.0
        val txt = if (centavos > 0) fmtPreco(v) else ""
        precos[n] = TextFieldValue(txt, TextRange(txt.length))
        val idx = items.indexOfFirst { it.itemNumber == n }
        if (idx < 0) return
        val cur = items[idx]
        items[idx] = cur.copy(unitPrice = v, selected = if (v > 0) cur.selected || cur.unitPrice <= 0 else false)
    }

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
                        Text(titulo, style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary)
                        Text(subtitulo, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "O robô vai localizar a compra, conferir que é a compra certa (UASG e número), verificar o prazo, aceitar os termos, " +
                                "ler o que já está lançado e preencher, salvar e reler SÓ os itens selecionados. Itens já lançados são pulados e informados.",
                            style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted,
                        )
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Atualizar itens já lançados com valor diferente", style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextPrimary)
                            Text(
                                if (updateDifferent) "O robô sobrescreve o valor do portal pelo daqui nesses itens."
                                else "Desligado: itens já lançados com outro valor NÃO são alterados.",
                                style = MaterialTheme.typography.labelSmall, color = if (updateDifferent) LicitaColors.Yellow else LicitaColors.TextMuted,
                            )
                        }
                        Switch(checked = updateDifferent, onCheckedChange = { updateDifferent = it })
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
                            "${sel.withoutPrice.size} item(ns) sem preço ficam de fora. Digite o valor unitário para incluir.",
                            style = MaterialTheme.typography.labelSmall, color = LicitaColors.Yellow,
                        )
                    }
                }
                items(items, key = { it.itemNumber }) { i ->
                    val idx = items.indexOfFirst { it.itemNumber == i.itemNumber }
                    LicitaCard(Modifier.fillMaxWidth(), accent = if (!i.hasPrice) LicitaColors.Yellow else null, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = i.selected, enabled = i.hasPrice,
                                onCheckedChange = { c -> if (idx >= 0 && (i.hasPrice || !c)) items[idx] = i.copy(selected = c) },
                            )
                            Column(Modifier.weight(1f)) {
                                Text("Item ${i.itemNumber} · qtd ${fmtQtd(i.quantity)}", style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                                if (i.description.isNotBlank()) Text(i.description, style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted, maxLines = 2)
                                i.floorUnitPrice?.let { Text("Piso do lance: ${Formatters.brl(it)}", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted) }
                                if (i.hasPrice) Text("Total do item: ${Formatters.brl(i.totalPrice)}", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextSecondary)
                            }
                            Spacer(Modifier.width(8.dp))
                            OutlinedTextField(
                                value = precos[i.itemNumber] ?: TextFieldValue(""), onValueChange = { setPreco(i.itemNumber, it) },
                                label = { Text("Valor unit. (R$)") }, placeholder = { Text("0,00") }, singleLine = true,
                                modifier = Modifier.width(150.dp),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            )
                        }
                    }
                }
                item {
                    HorizontalDivider(color = LicitaColors.Outline)
                    Spacer(Modifier.height(8.dp))
                    Text("Termo e declarações", style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary)
                    Text(
                        "O robô marca o Termo de Aceitação e, quando o portal abre o modal de declarações, usa “Marcar todas”, confere uma a uma e só então confirma. " +
                            "Se alguma ficar desmarcada, ele para sem confirmar. Depois aplica as respostas abaixo.",
                        style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted,
                    )
                    Spacer(Modifier.height(6.dp))
                    Declaracao("Declaração ME/EPP e equiparados", decl.meEpp) { decl = decl.copy(meEpp = it) }
                    Declaracao("Equidade entre mulheres e homens (art. 60, III)", decl.genderEquity) { decl = decl.copy(genderEquity = it) }
                    Declaracao("Programa de integridade (art. 60, IV)", decl.integrity) { decl = decl.copy(integrity = it) }
                    Row(Modifier.fillMaxWidth().clickable { accept = !accept }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = accept, onCheckedChange = { accept = it })
                        Text(TERMO_AUTORIZACAO, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Column(Modifier.fillMaxWidth().background(LicitaColors.Surface).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Total ofertado (${sel.label}): ${Formatters.brl(sel.selectedTotal)}", style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary)
                errors.firstOrNull()?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = LicitaColors.Yellow) }
                PrimaryButton(
                    "Soltar o robô", { onConfirm(items.toList(), decl, updateDifferent) }, Modifier.fillMaxWidth(),
                    enabled = errors.isEmpty() && !busy, loading = busy, icon = Icons.Outlined.SmartToy, tone = Tone.WARNING,
                )
            }
        }
    }
}

@Composable
private fun Declaracao(label: String, value: Boolean?, onChange: (Boolean) -> Unit) {
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

/** 14000.0 → "14.000,00" (padrão brasileiro, sem "R$"). */
private fun fmtPreco(v: Double): String = String.format(java.util.Locale("pt", "BR"), "%,.2f", v)

private fun fmtQtd(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else v.toString().replace('.', ',')
