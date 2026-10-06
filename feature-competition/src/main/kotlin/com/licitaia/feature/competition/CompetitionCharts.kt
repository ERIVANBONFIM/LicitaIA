package com.licitaia.feature.competition

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.licitaia.core.ui.theme.LicitaColors
import com.licitaia.domain.model.CompetitionRecord
import com.licitaia.domain.util.Formatters
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

@Composable
private fun rememberReveal(key: Any?): Float {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(key) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
    }
    return progress.value
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextSecondary)
    }
}

/** Linha da margem (%) por pregão em ordem cronológica; pontos verdes = vitória, vermelhos = derrota. */
@Composable
fun MarginChart(timeline: List<CompetitionRecord>, modifier: Modifier = Modifier) {
    if (timeline.isEmpty()) return
    val margins = timeline.map { it.ourMarginPct.toFloat() }
    val rawMin = min(margins.min(), 0f)
    val rawMax = max(margins.max(), 0f)
    // Escala arredondada para múltiplos de 5 com folga.
    val lo = floor((rawMin - 1f) / 5f) * 5f
    val hi = max(ceil((rawMax + 1f) / 5f) * 5f, lo + 5f)
    val reveal = rememberReveal(timeline.size to timeline.lastOrNull()?.id)

    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(170.dp)) {
            Column(Modifier.width(38.dp).height(170.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text("${hi.toInt()}%", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
                Text("${((hi + lo) / 2).toInt()}%", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
                Text("${lo.toInt()}%", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
            }
            Canvas(Modifier.weight(1f).height(170.dp)) {
                val padV = 8.dp.toPx()
                val padH = 8.dp.toPx()
                val w = size.width - padH * 2
                val h = size.height - padV * 2
                fun y(v: Float) = padV + h * (1f - (v - lo) / (hi - lo))
                fun x(i: Int) = if (margins.size == 1) padH + w / 2 else padH + w * i / (margins.size - 1)

                // Grade horizontal
                for (g in 0..4) {
                    val gy = padV + h * g / 4f
                    drawLine(LicitaColors.Outline, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1.dp.toPx())
                }
                // Linha do zero (margem nula)
                if (lo < 0f) {
                    drawLine(
                        LicitaColors.Red.copy(alpha = 0.6f), Offset(0f, y(0f)), Offset(size.width, y(0f)),
                        strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
                    )
                }

                val points = margins.mapIndexed { i, v -> Offset(x(i), y(v)) }
                clipRect(right = size.width * reveal) {
                    if (points.size > 1) {
                        val line = Path().apply {
                            moveTo(points.first().x, points.first().y)
                            points.drop(1).forEach { lineTo(it.x, it.y) }
                        }
                        val area = Path().apply {
                            addPath(line)
                            lineTo(points.last().x, padV + h)
                            lineTo(points.first().x, padV + h)
                            close()
                        }
                        drawPath(area, Brush.verticalGradient(listOf(LicitaColors.Blue.copy(alpha = 0.28f), Color.Transparent)))
                        drawPath(line, LicitaColors.BlueBright, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                    points.forEachIndexed { i, p ->
                        drawCircle(LicitaColors.Surface, radius = 5.5.dp.toPx(), center = p)
                        drawCircle(if (timeline[i].won) LicitaColors.Green else LicitaColors.Red, radius = 3.8.dp.toPx(), center = p)
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().padding(start = 38.dp)) {
            Text(Formatters.date(timeline.first().date), style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted, modifier = Modifier.weight(1f))
            if (timeline.size > 1) {
                Text(Formatters.date(timeline.last().date), style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Legend(LicitaColors.Green, "Vitória")
            Legend(LicitaColors.Red, "Derrota")
            Legend(LicitaColors.BlueBright, "Margem do lance final")
        }
    }
}

/** Barras: quantidade de pregões por faixa de desconto; trecho verde = vitórias nossas na faixa. */
@Composable
fun BandsChart(bands: List<DiscountBand>, modifier: Modifier = Modifier) {
    if (bands.isEmpty()) return
    val maxCount = max(bands.maxOf { it.count }, 1)
    val reveal = rememberReveal(bands.map { it.count })

    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            bands.forEach { b ->
                Text(
                    "${b.count}", style = MaterialTheme.typography.labelMedium,
                    color = if (b.count > 0) LicitaColors.TextPrimary else LicitaColors.TextMuted,
                    fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Canvas(Modifier.fillMaxWidth().height(130.dp)) {
            val slot = size.width / bands.size
            val barW = slot * 0.56f
            val radius = CornerRadius(6.dp.toPx())
            drawLine(LicitaColors.Outline, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
            bands.forEachIndexed { i, b ->
                val left = slot * i + (slot - barW) / 2
                val fullH = size.height * b.count / maxCount * reveal
                val winH = size.height * b.wins / maxCount * reveal
                // Trilho de fundo
                drawRoundRect(LicitaColors.SurfaceHigh.copy(alpha = 0.5f), Offset(left, 0f), Size(barW, size.height), radius)
                if (fullH > 0f) drawRoundRect(LicitaColors.Blue, Offset(left, size.height - fullH), Size(barW, fullH), radius)
                if (winH > 0f) drawRoundRect(LicitaColors.Green, Offset(left, size.height - winH), Size(barW, winH), radius)
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            bands.forEach { b ->
                Text(b.label, style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextSecondary, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Legend(LicitaColors.Blue, "Pregões na faixa")
            Legend(LicitaColors.Green, "Vencidos por nós")
        }
    }
}

/** Rosca de vitórias × derrotas com a taxa no centro. */
@Composable
fun WinLossChart(wins: Int, losses: Int, modifier: Modifier = Modifier) {
    val total = wins + losses
    if (total == 0) return
    val reveal = rememberReveal(wins to losses)
    val winSweep = 360f * wins / total

    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(120.dp)) {
                val stroke = 16.dp.toPx()
                val arcSize = Size(size.width - stroke, size.height - stroke)
                val topLeft = Offset(stroke / 2, stroke / 2)
                drawArc(LicitaColors.Outline, -90f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
                if (losses > 0) drawArc(LicitaColors.Red, -90f + winSweep, (360f - winSweep) * reveal, false, topLeft, arcSize, style = Stroke(stroke))
                if (wins > 0) drawArc(LicitaColors.Green, -90f, winSweep * reveal, false, topLeft, arcSize, style = Stroke(stroke))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${wins * 100 / total}%", style = MaterialTheme.typography.headlineSmall, color = LicitaColors.TextPrimary)
                Text("vitórias", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextSecondary)
            }
        }
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CountRow(LicitaColors.Green, "Vitórias", wins)
            CountRow(LicitaColors.Red, "Derrotas", losses)
            CountRow(LicitaColors.TextSecondary, "Total", total)
        }
    }
}

@Composable
private fun CountRow(color: Color, label: String, count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextSecondary, modifier = Modifier.weight(1f))
        Text("$count", style = MaterialTheme.typography.titleMedium, color = LicitaColors.TextPrimary)
    }
}
