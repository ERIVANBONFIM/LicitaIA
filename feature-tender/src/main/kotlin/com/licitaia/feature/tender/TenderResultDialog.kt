package com.licitaia.feature.tender

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.licitaia.core.ui.theme.LicitaColors
import com.licitaia.domain.model.CompetitionRecord
import com.licitaia.domain.model.Tender
import com.licitaia.domain.util.Formatters

/** Campos do diálogo "Registrar resultado" (texto bruto; validado em [TenderResultForm.toRecord]). */
data class TenderResultForm(
    val won: Boolean,
    val competitors: String = "",
    val closingValue: String = "",
    val ourFinalBid: String = "",
    val bidsCount: String = "",
    /** Custo total (opcional) — base da margem; sem custo a margem fica 0. */
    val cost: String = "",
    val behavior: String = "",
) {
    /**
     * Monta o [CompetitionRecord] com segmento/portal/órgão da licitação. Devolve mensagem de erro
     * (pt-BR) em caso de campo inválido.
     */
    fun toRecord(tender: Tender, now: Long = System.currentTimeMillis()): Result<CompetitionRecord> {
        val competitorsN = competitors.trim().toIntOrNull() ?: return Result.failure(IllegalArgumentException("Informe o número de concorrentes (0 se não houve outros)."))
        if (competitorsN < 0) return Result.failure(IllegalArgumentException("Número de concorrentes inválido."))
        val closing = parseNumber(closingValue)
        if (closing == null || closing.isNaN() || closing < 0.0) return Result.failure(IllegalArgumentException("Informe o valor de fechamento do pregão."))
        val bid = parseNumber(ourFinalBid)
        if (bid == null || bid.isNaN() || bid < 0.0) return Result.failure(IllegalArgumentException("Informe o nosso lance final."))
        val bids = bidsCount.trim().ifEmpty { "0" }.toIntOrNull() ?: return Result.failure(IllegalArgumentException("Número de lances inválido."))
        if (bids < 0) return Result.failure(IllegalArgumentException("Número de lances inválido."))
        val costValue = parseNumber(cost)
        if (costValue != null && (costValue.isNaN() || costValue < 0.0)) return Result.failure(IllegalArgumentException("Custo inválido."))
        return Result.success(
            CompetitionRecord(
                companyId = tender.companyId,
                portal = tender.portal,
                tenderNumber = tender.number,
                agency = tender.agency,
                segment = tender.segment,
                objectSummary = tender.objectDescription.take(200),
                date = now,
                competitors = competitorsN,
                estimatedValue = tender.estimatedValue,
                closingValue = closing,
                ourFinalBid = bid,
                won = won,
                ourMarginPct = marginPct(bid, costValue),
                bidsCount = bids,
                behavior = behavior.trim(),
            ),
        )
    }

    companion object {
        /** Margem (%) do lance sobre o custo; 0 quando não há custo ou lance. */
        fun marginPct(bid: Double, cost: Double?): Double =
            if (cost == null || bid <= 0.0) 0.0 else (bid - cost) / bid * 100.0
    }
}

/**
 * Diálogo "Registrar resultado": nº de concorrentes, fechamento, nosso lance final, nº de lances,
 * custo (opcional, para a margem) e observação de comportamento. [onSkip] (opcional) só altera o
 * status sem gravar na Concorrência.
 */
@Composable
internal fun TenderResultDialog(
    tender: Tender,
    initialWon: Boolean,
    suggestedBid: Double?,
    allowToggleOutcome: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (TenderResultForm) -> Unit,
    onSkip: ((won: Boolean) -> Unit)? = null,
) {
    var form by remember {
        mutableStateOf(
            TenderResultForm(
                won = initialWon,
                ourFinalBid = suggestedBid?.takeIf { it > 0 }?.let(::numberText) ?: "",
                closingValue = if (initialWon) suggestedBid?.takeIf { it > 0 }?.let(::numberText) ?: "" else "",
            ),
        )
    }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Registrar resultado") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "${tender.portal.shortName} ${tender.number} · ${tender.agency}",
                    style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary,
                )
                Text(
                    "Estimado ${Formatters.brl(tender.estimatedValue)}. Os dados alimentam a análise de concorrência (margem, descontos, taxa de vitória).",
                    style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted,
                )
                Spacer(Modifier.height(10.dp))
                if (allowToggleOutcome) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SelectChip("Vencemos", form.won, { form = form.copy(won = true) }, color = LicitaColors.Green)
                        SelectChip("Perdemos", !form.won, { form = form.copy(won = false) }, color = LicitaColors.Red)
                    }
                    Spacer(Modifier.height(10.dp))
                }
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
                    supportingText = { Text(if (form.won) "Vencemos: normalmente igual ao nosso lance final." else "Lance vencedor do concorrente.") },
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
                        val bid = parseNumber(form.ourFinalBid)
                        val cost = parseNumber(form.cost)
                        Text(
                            if (cost != null && bid != null && !cost.isNaN() && !bid.isNaN() && bid > 0) {
                                "Margem calculada: ${Formatters.percent(TenderResultForm.marginPct(bid, cost))}"
                            } else {
                                "Sem custo, a margem fica registrada como 0%."
                            },
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
                if (onSkip != null) {
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = { onSkip(form.won) }) { Text("Só alterar o status, sem registrar", color = LicitaColors.TextSecondary) }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    form.toRecord(tender).fold(
                        onSuccess = { onConfirm(form) },
                        onFailure = { error = it.message },
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = if (form.won) LicitaColors.Green else LicitaColors.Blue),
            ) { Text(if (form.won) "Salvar vitória" else "Salvar derrota") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
        containerColor = LicitaColors.SurfaceElevated,
    )
}
