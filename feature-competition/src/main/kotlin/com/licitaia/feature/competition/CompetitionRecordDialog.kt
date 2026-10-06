package com.licitaia.feature.competition

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.licitaia.core.ui.components.SelectChip
import com.licitaia.core.ui.components.color
import com.licitaia.core.ui.theme.LicitaColors
import com.licitaia.domain.model.CompetitionRecord
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.Segment
import com.licitaia.domain.util.Formatters
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Formulário do registro manual de resultado (texto bruto; validado em [toRecord]). */
data class CompetitionRecordForm(
    val won: Boolean = true,
    val portal: Portal = Portal.PNCP,
    val segment: Segment = Segment.SERVICOS,
    val tenderNumber: String = "",
    val agency: String = "",
    val objectSummary: String = "",
    /** dd/MM/yyyy */
    val date: String = "",
    val estimatedValue: String = "",
    val competitors: String = "",
    val closingValue: String = "",
    val ourFinalBid: String = "",
    val bidsCount: String = "",
    /** Custo total (opcional) — base da margem; sem custo a margem fica 0. */
    val cost: String = "",
    val behavior: String = "",
) {
    fun toRecord(companyId: Long): Result<CompetitionRecord> {
        if (tenderNumber.trim().length < 3) return fail("Informe o número da licitação (ex.: 90012/2026).")
        if (agency.trim().length < 3) return fail("Informe o órgão licitante.")
        if (objectSummary.trim().length < 5) return fail("Descreva o objeto em poucas palavras.")
        val dateMillis = parseDate(date) ?: return fail("Data inválida. Use o formato dd/mm/aaaa.")
        val estimated = parseMoney(estimatedValue) ?: return fail("Valor estimado inválido.")
        val competitorsN = competitors.trim().toIntOrNull()?.takeIf { it >= 0 } ?: return fail("Informe o número de concorrentes (0 se não houve outros).")
        val closing = parseMoney(closingValue) ?: return fail("Informe o valor de fechamento do pregão.")
        val bid = parseMoney(ourFinalBid) ?: return fail("Informe o nosso lance final.")
        val bids = bidsCount.trim().ifEmpty { "0" }.toIntOrNull()?.takeIf { it >= 0 } ?: return fail("Número de lances inválido.")
        val costValue = if (cost.isBlank()) null else parseMoney(cost) ?: return fail("Custo inválido.")
        return Result.success(
            CompetitionRecord(
                companyId = companyId, portal = portal, tenderNumber = tenderNumber.trim(), agency = agency.trim(),
                segment = segment, objectSummary = objectSummary.trim().take(200), date = dateMillis,
                competitors = competitorsN, estimatedValue = estimated, closingValue = closing, ourFinalBid = bid,
                won = won, ourMarginPct = marginPct(bid, costValue), bidsCount = bids, behavior = behavior.trim(),
            ),
        )
    }

    private fun fail(message: String): Result<CompetitionRecord> = Result.failure(IllegalArgumentException(message))

    companion object {
        private val DATE_FORMAT get() = SimpleDateFormat("dd/MM/yyyy", Locale("pt", "BR")).apply { isLenient = false }

        fun today(): String = DATE_FORMAT.format(Date())

        fun parseDate(text: String): Long? = try {
            DATE_FORMAT.parse(text.trim())?.time
        } catch (_: ParseException) {
            null
        }

        /** Aceita "1.234,56", "1234.56" e "1234". Vazio/inválido/negativo → null. */
        fun parseMoney(text: String): Double? {
            val raw = text.trim().replace("R$", "").replace(" ", "")
            if (raw.isEmpty()) return null
            val normalized = if (raw.contains(',')) raw.replace(".", "").replace(',', '.') else raw
            return normalized.toDoubleOrNull()?.takeIf { !it.isNaN() && it >= 0.0 }
        }

        /** Margem (%) do lance sobre o custo; 0 quando não há custo ou lance. */
        fun marginPct(bid: Double, cost: Double?): Double =
            if (cost == null || bid <= 0.0) 0.0 else (bid - cost) / bid * 100.0
    }
}

@Composable
internal fun CompetitionRecordDialog(
    defaultSegment: Segment?,
    onDismiss: () -> Unit,
    onConfirm: (CompetitionRecordForm) -> Unit,
) {
    var form by remember { mutableStateOf(CompetitionRecordForm(segment = defaultSegment ?: Segment.SERVICOS, date = CompetitionRecordForm.today())) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Registrar resultado") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Resultado real de um pregão do qual a empresa participou. Alimenta taxa de vitória, descontos, margem e comportamento de lances.",
                    style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectChip("Vencemos", form.won, { form = form.copy(won = true) }, color = LicitaColors.Green)
                    SelectChip("Perdemos", !form.won, { form = form.copy(won = false) }, color = LicitaColors.Red)
                }
                Spacer(Modifier.height(10.dp))
                Text("Portal", style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextSecondary)
                Spacer(Modifier.height(4.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(Portal.entries) { p -> SelectChip(p.shortName, form.portal == p, { form = form.copy(portal = p) }, color = p.color()) }
                }
                Spacer(Modifier.height(8.dp))
                Text("Segmento", style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextSecondary)
                Spacer(Modifier.height(4.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(Segment.entries) { s -> SelectChip(s.label, form.segment == s, { form = form.copy(segment = s) }) }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = form.tenderNumber, onValueChange = { form = form.copy(tenderNumber = it) },
                    label = { Text("Nº do edital") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = form.agency, onValueChange = { form = form.copy(agency = it) },
                    label = { Text("Órgão") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = form.objectSummary, onValueChange = { form = form.copy(objectSummary = it) },
                    label = { Text("Objeto (resumo)") }, minLines = 1, maxLines = 3, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = form.date, onValueChange = { form = form.copy(date = it) },
                        label = { Text("Data (dd/mm/aaaa)") }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        value = form.estimatedValue, onValueChange = { form = form.copy(estimatedValue = it) },
                        label = { Text("Estimado (R$)") }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = form.competitors, onValueChange = { form = form.copy(competitors = it.filter(Char::isDigit)) },
                        label = { Text("Concorrentes") }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        value = form.bidsCount, onValueChange = { form = form.copy(bidsCount = it.filter(Char::isDigit)) },
                        label = { Text("Nº de lances") }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = form.closingValue, onValueChange = { form = form.copy(closingValue = it) },
                    label = { Text("Valor de fechamento (R$)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = form.ourFinalBid, onValueChange = { form = form.copy(ourFinalBid = it) },
                    label = { Text("Nosso lance final (R$)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = form.cost, onValueChange = { form = form.copy(cost = it) },
                    label = { Text("Custo total (R$, opcional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    supportingText = {
                        val bid = CompetitionRecordForm.parseMoney(form.ourFinalBid)
                        val cost = CompetitionRecordForm.parseMoney(form.cost)
                        Text(
                            if (bid != null && cost != null && bid > 0) "Margem calculada: ${Formatters.percent(CompetitionRecordForm.marginPct(bid, cost))}"
                            else "Sem custo, a margem fica registrada como 0%.",
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = form.behavior, onValueChange = { form = form.copy(behavior = it) },
                    label = { Text("Comportamento observado (opcional)") }, minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Ex.: concorrente cobriu cada lance em menos de 10 s até o piso") },
                )
                val message = error
                if (message != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(message, style = MaterialTheme.typography.bodySmall, color = LicitaColors.RedBright)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { form.toRecord(0L).fold(onSuccess = { onConfirm(form) }, onFailure = { error = it.message }) },
                colors = ButtonDefaults.buttonColors(containerColor = if (form.won) LicitaColors.Green else LicitaColors.Blue),
            ) { Text(if (form.won) "Salvar vitória" else "Salvar derrota") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
        containerColor = LicitaColors.SurfaceElevated,
    )
}
