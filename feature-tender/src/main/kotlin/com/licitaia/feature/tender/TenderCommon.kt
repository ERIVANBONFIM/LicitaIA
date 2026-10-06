package com.licitaia.feature.tender

import android.content.Context
import android.content.Intent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import com.licitaia.core.ui.components.PortalChip
import com.licitaia.core.ui.components.ScoreRing
import com.licitaia.core.ui.components.StatusBadge
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.components.tone
import com.licitaia.core.ui.theme.LicitaColors
import com.licitaia.domain.model.CompanyDocument
import com.licitaia.domain.model.Proposal
import com.licitaia.domain.model.ProposalStatus
import com.licitaia.domain.model.Tender
import com.licitaia.domain.model.TenderAnalysis
import com.licitaia.domain.model.TenderStatus
import com.licitaia.domain.util.Formatters
import java.io.File

/** Argumentos de rota chegam como String (sem navArgument tipado) — aceita ambos. */
internal fun SavedStateHandle.longArg(name: String): Long? = get<Any?>(name)?.toString()?.toLongOrNull()

/** Aceita "1.234,56", "1234.56" e "1234". Vazio → null. Inválido → NaN. */
internal fun parseNumber(text: String): Double? {
    val raw = text.trim().replace("R$", "").replace(" ", "")
    if (raw.isEmpty()) return null
    val normalized = if (raw.contains(',')) raw.replace(".", "").replace(',', '.') else raw
    return normalized.toDoubleOrNull() ?: Double.NaN
}

internal fun numberText(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else String.format(java.util.Locale.US, "%.2f", value).replace('.', ',')

internal fun daysUntil(millis: Long, now: Long = System.currentTimeMillis()): Int = ((millis - now) / CompanyDocument.DAY_MS).toInt()

internal fun deadlineTone(millis: Long): Tone {
    val days = daysUntil(millis)
    return when {
        days < 0 -> Tone.NEUTRAL
        days <= 2 -> Tone.DANGER
        days <= 7 -> Tone.WARNING
        else -> Tone.INFO
    }
}

internal fun deadlineLabel(millis: Long): String {
    val days = daysUntil(millis)
    return when {
        days < 0 -> "Prazo encerrado"
        days == 0 -> "Encerra hoje"
        days == 1 -> "Encerra amanhã"
        else -> "Encerra em $days dias"
    }
}

/** Status a partir dos quais a licitação conta como "participação". */
internal fun TenderStatus.isParticipation(): Boolean =
    ordinal >= TenderStatus.AGUARDANDO_APROVACAO.ordinal && this != TenderStatus.DESCARTADA

internal const val FILE_PROVIDER_SUFFIX = ".licitaia.fileprovider"

/** Compartilha um PDF privado via FileProvider. Retorna false quando o arquivo não existe. */
internal fun sharePdf(context: Context, path: String, title: String): Boolean {
    val file = File(path)
    if (!file.exists()) return false
    val uri = FileProvider.getUriForFile(context, context.packageName + FILE_PROVIDER_SUFFIX, file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, title)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(intent, "Compartilhar proposta").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    return try {
        context.startActivity(chooser)
        true
    } catch (_: Exception) {
        false
    }
}

/** Abre um PDF privado (edital importado) em um leitor externo via FileProvider. false = arquivo ausente/sem app. */
internal fun openPdf(context: Context, path: String): Boolean {
    val file = File(path)
    if (!file.exists()) return false
    val uri = FileProvider.getUriForFile(context, context.packageName + FILE_PROVIDER_SUFFIX, file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/pdf")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
        context.startActivity(intent)
        true
    } catch (_: Exception) {
        false
    }
}

// ------------------------------------------------------------------ Linha do tempo do fluxo

internal enum class FlowStep(val label: String) {
    INTERESSE("Interesse"), ANALISE("Análise"), PROPOSTA("Proposta"), APROVACAO("Aprovação"), ENVIO("Envio simulado"),
}

/** Quantas etapas do fluxo "Tenho Interesse" já foram concluídas (0..5). */
internal fun completedSteps(tender: Tender, analysis: TenderAnalysis?, proposals: List<Proposal>): Int {
    val sent = proposals.any { it.status == ProposalStatus.ENVIADA_SIMULADA } || tender.status.ordinal >= TenderStatus.ENVIADA_SIMULADA.ordinal && tender.status != TenderStatus.DESCARTADA
    val approved = sent || proposals.any { it.status == ProposalStatus.APROVADA } || tender.status == TenderStatus.APROVADA || tender.status == TenderStatus.PRONTA_PARA_ENVIO
    val proposal = approved || proposals.isNotEmpty()
    val analyzed = proposal || analysis != null
    return when {
        sent -> 5
        approved -> 4
        proposal -> 3
        analyzed -> 2
        else -> 1
    }
}

@Composable
internal fun FlowTimeline(completed: Int, modifier: Modifier = Modifier) {
    val steps = FlowStep.entries
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            steps.forEachIndexed { index, _ ->
                val done = index < completed
                val current = index == completed
                val color by animateColorAsState(
                    when {
                        done -> LicitaColors.Green
                        current -> LicitaColors.Blue
                        else -> LicitaColors.Outline
                    },
                    label = "step",
                )
                Box(
                    Modifier.size(22.dp).clip(CircleShape).background(color),
                    contentAlignment = Alignment.Center,
                ) {
                    if (done) {
                        Icon(Icons.Outlined.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                    } else {
                        Text("${index + 1}", style = MaterialTheme.typography.labelSmall, color = if (current) Color.White else LicitaColors.TextMuted, fontWeight = FontWeight.Bold)
                    }
                }
                if (index < steps.lastIndex) {
                    val lineColor by animateColorAsState(if (index < completed - 1) LicitaColors.Green else LicitaColors.Outline, label = "line")
                    Box(Modifier.weight(1f).height(2.dp).background(lineColor))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row {
            steps.forEachIndexed { index, step ->
                Text(
                    step.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (index < completed) LicitaColors.TextPrimary else LicitaColors.TextMuted,
                    textAlign = when (index) {
                        0 -> TextAlign.Start
                        steps.lastIndex -> TextAlign.End
                        else -> TextAlign.Center
                    },
                    maxLines = 2,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

// ------------------------------------------------------------------ Cabeçalho resumido da licitação

@Composable
internal fun TenderHeadline(tender: Tender, analysis: TenderAnalysis?, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (analysis != null) {
            ScoreRing(analysis.fit.overall, size = 56.dp, strokeWidth = 5.dp)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PortalChip(tender.portal)
                StatusBadge(tender.status.label, tender.status.tone())
            }
            Spacer(Modifier.height(6.dp))
            Text(tender.number, style = MaterialTheme.typography.titleMedium, color = LicitaColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(tender.agency, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun ValueDateStrip(tender: Tender) {
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1.2f)) {
            Text("Valor estimado", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
            Text(Formatters.brlCompact(tender.estimatedValue), style = MaterialTheme.typography.titleSmall, color = LicitaColors.GreenBright, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        Column(Modifier.weight(1.4f)) {
            Text("Propostas até", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
            Text(Formatters.dateTime(tender.proposalDeadline), style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextPrimary, maxLines = 1)
        }
        Column(Modifier.weight(1.4f)) {
            Text("Sessão", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
            Text(Formatters.dateTime(tender.sessionAt), style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextPrimary, maxLines = 1)
        }
    }
}
