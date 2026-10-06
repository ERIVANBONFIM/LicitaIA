package com.licitaia.feature.bidding.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.licitaia.core.ui.theme.LicitaColors
import com.licitaia.domain.bidding.SimulatedBid
import com.licitaia.domain.model.BidStrategy
import com.licitaia.domain.model.RobotMode
import com.licitaia.domain.util.Formatters
import kotlin.math.max
import kotlin.math.min

/** Converte "1.234,56", "1234.56" ou "1234" em Double; null se inválido. */
fun parseBrl(text: String): Double? {
    val cleaned = text.trim().removePrefix("R$").trim()
    if (cleaned.isBlank()) return null
    val normalized = if (cleaned.contains(',')) cleaned.replace(".", "").replace(',', '.') else cleaned
    return normalized.toDoubleOrNull()?.takeIf { !it.isNaN() && !it.isInfinite() }
}

fun formatInput(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else String.format(java.util.Locale.US, "%.2f", value)

@Composable
fun NumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    suffix: String? = null,
    error: String? = null,
    enabled: Boolean = true,
    supporting: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        isError = error != null,
        suffix = suffix?.let { { Text(it, color = LicitaColors.TextMuted) } },
        supportingText = (error ?: supporting)?.let { { Text(it, color = if (error != null) LicitaColors.Red else LicitaColors.TextMuted) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = LicitaColors.Blue,
            unfocusedBorderColor = LicitaColors.Outline,
            focusedLabelColor = LicitaColors.BlueBright,
            unfocusedLabelColor = LicitaColors.TextSecondary,
            cursorColor = LicitaColors.Blue,
            focusedTextColor = LicitaColors.TextPrimary,
            unfocusedTextColor = LicitaColors.TextPrimary,
            disabledTextColor = LicitaColors.TextMuted,
            disabledBorderColor = LicitaColors.OutlineSoft,
        ),
    )
}

fun BidStrategy.color(): Color = when (this) {
    BidStrategy.CONSERVADORA -> LicitaColors.GreenBright
    BidStrategy.AGRESSIVA -> LicitaColors.RedBright
    BidStrategy.ACOMPANHAR_CONCORRENTE -> LicitaColors.BlueBright
    BidStrategy.PERSONALIZADA -> LicitaColors.Purple
}

fun RobotMode.short(): String = when (this) {
    RobotMode.MANUAL -> "Manual"
    RobotMode.SUPERVISIONADO -> "Supervisionado"
    RobotMode.AUTOMATICO_LIMITADO -> "Auto. limitado"
}

/**
 * Gráfico em Canvas da evolução dos lances: linha dos concorrentes, pontos dos nossos lances,
 * linha tracejada do piso. Animação de entrada discreta.
 */
@Composable
fun BidChart(
    series: List<SimulatedBid>,
    floorPrice: Double,
    initialPrice: Double,
    durationSeconds: Int,
    modifier: Modifier = Modifier,
    ourColor: Color = LicitaColors.GreenBright,
    competitorColor: Color = LicitaColors.BlueBright,
) {
    var started by remember(series) { mutableStateOf(false) }
    LaunchedEffect(series) { started = true }
    val progress by animateFloatAsState(if (started) 1f else 0f, tween(900), label = "chart")

    Column(modifier.fillMaxWidth()) {
        val maxValue = max(initialPrice, series.maxOfOrNull { it.value } ?: initialPrice)
        val minValue = min(floorPrice, series.minOfOrNull { it.value } ?: floorPrice)
        val range = (maxValue - minValue).takeIf { it > 0 } ?: 1.0
        val totalSeconds = max(durationSeconds, series.lastOrNull()?.second ?: 1).coerceAtLeast(1)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(Formatters.brlCompact(maxValue), style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
            Text("piso ${Formatters.brlCompact(floorPrice)}", style = MaterialTheme.typography.labelSmall, color = LicitaColors.Yellow)
        }
        Canvas(Modifier.fillMaxWidth().height(180.dp).padding(vertical = 6.dp)) {
            val w = size.width
            val h = size.height
            fun x(second: Int) = (second.toFloat() / totalSeconds) * w
            fun y(value: Double) = (h - ((value - minValue) / range * h)).toFloat()

            // grade
            for (i in 0..3) {
                val gy = h * i / 3f
                drawLine(LicitaColors.OutlineSoft, Offset(0f, gy), Offset(w, gy), strokeWidth = 1f)
            }
            // piso
            drawLine(
                LicitaColors.Yellow.copy(alpha = 0.8f), Offset(0f, y(floorPrice)), Offset(w, y(floorPrice)),
                strokeWidth = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)),
            )
            if (series.isEmpty()) return@Canvas
            val visible = series.take(max(1, (series.size * progress).toInt()))

            // linha do melhor lance (todos os lances em ordem)
            val path = Path()
            path.moveTo(x(0), y(initialPrice))
            visible.forEach { b -> path.lineTo(x(b.second), y(b.value)) }
            drawPath(path, competitorColor.copy(alpha = 0.85f), style = Stroke(width = 3f, cap = StrokeCap.Round))

            // nossos lances em destaque
            visible.filter { it.ours }.forEach { b ->
                drawCircle(ourColor, radius = 5.5f, center = Offset(x(b.second), y(b.value)))
                drawCircle(LicitaColors.Background, radius = 2.2f, center = Offset(x(b.second), y(b.value)))
            }
            visible.filter { !it.ours }.forEach { b ->
                drawCircle(competitorColor.copy(alpha = 0.5f), radius = 2.5f, center = Offset(x(b.second), y(b.value)))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(Formatters.brlCompact(minValue), style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
            Text(Formatters.countdown(totalSeconds), style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LegendDot(ourColor, "Nossos lances")
            LegendDot(competitorColor, "Concorrentes")
            LegendDot(LicitaColors.Yellow, "Piso")
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(5.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextSecondary)
    }
}
