package com.licitaia.core.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.licitaia.core.ui.theme.LicitaColors

/** Tom semântico: verde sucesso, amarelo atenção, vermelho crítico, azul ação/informação. */
enum class Tone { SUCCESS, WARNING, DANGER, INFO, NEUTRAL }

fun Tone.color(): Color = when (this) {
    Tone.SUCCESS -> LicitaColors.Green
    Tone.WARNING -> LicitaColors.Yellow
    Tone.DANGER -> LicitaColors.Red
    Tone.INFO -> LicitaColors.Blue
    Tone.NEUTRAL -> LicitaColors.TextSecondary
}

fun Tone.icon(): ImageVector = when (this) {
    Tone.SUCCESS -> Icons.Outlined.CheckCircle
    Tone.WARNING -> Icons.Outlined.WarningAmber
    Tone.DANGER -> Icons.Outlined.ErrorOutline
    else -> Icons.Outlined.Info
}

// ------------------------------------------------------------------ Cards

/** Card padrão. [accent] desenha uma borda colorida sutil (ex.: status crítico). */
@Composable
fun LicitaCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    accent: Color? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    val border = BorderStroke(1.dp, accent?.copy(alpha = 0.55f) ?: LicitaColors.Outline)
    val inner: @Composable () -> Unit = {
        Column(Modifier.padding(contentPadding), content = content)
    }
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier, shape = shape, color = LicitaColors.Surface, border = border, content = inner)
    } else {
        Surface(modifier = modifier, shape = shape, color = LicitaColors.Surface, border = border, content = inner)
    }
}

/** Card de destaque com gradiente discreto (hero do dashboard, decisão executiva...). */
@Composable
fun GradientCard(
    modifier: Modifier = Modifier,
    brush: Brush = LicitaColors.HeroGradient,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.extraLarge
    Column(
        modifier
            .clip(shape)
            .background(brush)
            .border(1.dp, LicitaColors.Outline, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(20.dp),
        content = content,
    )
}

/** Indicador numérico do dashboard / Sala de Guerra. */
@Composable
fun StatCard(
    label: String,
    value: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.INFO,
    highlight: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val color = tone.color()
    LicitaCard(modifier = modifier, onClick = onClick, accent = if (highlight) color else null, contentPadding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(icon, color, size = 36.dp)
            Spacer(Modifier.weight(1f))
            if (highlight) PulsingDot(color)
        }
        Spacer(Modifier.height(12.dp))
        Text(value, style = MaterialTheme.typography.headlineMedium, color = LicitaColors.TextPrimary, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun IconBubble(icon: ImageVector, color: Color, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size / 3.2f))
            .background(color.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(size * 0.55f))
    }
}

// ------------------------------------------------------------------ Badges / indicadores

@Composable
fun StatusBadge(text: String, tone: Tone, modifier: Modifier = Modifier, pulsing: Boolean = false) {
    val color = tone.color()
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (pulsing) {
            PulsingDot(color, size = 7.dp)
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Ponto pulsante — indica algo "ao vivo" ou pendente. */
@Composable
fun PulsingDot(color: Color, modifier: Modifier = Modifier, size: Dp = 9.dp) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val scale by transition.animateFloat(
        initialValue = 0.7f, targetValue = 1.25f,
        animationSpec = infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "scale",
    )
    val alpha by transition.animateFloat(
        initialValue = 0.55f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "alpha",
    )
    Box(modifier.size(size).scale(scale).alpha(alpha).clip(CircleShape).background(color))
}

fun scoreTone(score: Int): Tone = when {
    score >= 70 -> Tone.SUCCESS
    score >= 45 -> Tone.WARNING
    else -> Tone.DANGER
}

/** Anel animado de score 0..100. */
@Composable
fun ScoreRing(
    score: Int,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    strokeWidth: Dp = 6.dp,
    tone: Tone = scoreTone(score),
    label: String? = null,
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(score) { progress.animateTo(score.coerceIn(0, 100) / 100f, tween(900, easing = FastOutSlowInEasing)) }
    val color = tone.color()
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = strokeWidth.toPx()
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            val topLeft = Offset(stroke / 2, stroke / 2)
            drawArc(LicitaColors.Outline, -90f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
            drawArc(color, -90f, 360f * progress.value, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "${(progress.value * 100).toInt()}",
                color = LicitaColors.TextPrimary, fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.30f).sp,
            )
            if (label != null) Text(label, style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextSecondary)
        }
    }
}

/** Barra horizontal 0..100 com rótulo — usada nos indicadores de aderência. */
@Composable
fun LinearMeter(
    label: String,
    value: Int,
    modifier: Modifier = Modifier,
    tone: Tone = scoreTone(value),
    valueText: String = "$value",
) {
    val animated by animateFloatAsState(value.coerceIn(0, 100) / 100f, tween(700), label = "meter")
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextSecondary, modifier = Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.labelLarge, color = tone.color())
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(LicitaColors.Outline)) {
            Box(Modifier.fillMaxWidth(animated).height(6.dp).clip(RoundedCornerShape(50)).background(tone.color()))
        }
    }
}

@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = LicitaColors.TextPrimary) {
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextSecondary, modifier = Modifier.weight(0.42f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor, fontWeight = FontWeight.Medium, modifier = Modifier.weight(0.58f), textAlign = TextAlign.End)
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = LicitaColors.TextPrimary, modifier = Modifier.weight(1f))
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

// ------------------------------------------------------------------ Botões

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
    tone: Tone = Tone.INFO,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.heightIn(min = 50.dp),
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(
            containerColor = tone.color(),
            contentColor = if (tone == Tone.WARNING) Color(0xFF231600) else Color.White,
            disabledContainerColor = LicitaColors.SurfaceHigh,
            disabledContentColor = LicitaColors.TextMuted,
        ),
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LicitaColors.TextPrimary)
            Spacer(Modifier.width(10.dp))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    tone: Tone = Tone.INFO,
) {
    val color = if (tone == Tone.NEUTRAL) LicitaColors.TextPrimary else tone.color()
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 50.dp),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, if (enabled) color.copy(alpha = 0.6f) else LicitaColors.Outline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Botão grande para ações críticas (ex.: PAUSAR TODOS OS ROBÔS, PARAR E ASSUMIR). */
@Composable
fun DangerButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 60.dp),
        shape = MaterialTheme.shapes.large,
        colors = ButtonDefaults.buttonColors(containerColor = LicitaColors.Red, contentColor = Color.White),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

// ------------------------------------------------------------------ Estados (loading / vazio / erro)

@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Outlined.Inbox,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        IconBubble(icon, LicitaColors.Blue, size = 72.dp)
        Spacer(Modifier.height(18.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = LicitaColors.TextPrimary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextSecondary, textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            PrimaryButton(actionLabel, onAction)
        }
    }
}

@Composable
fun ErrorState(message: String, modifier: Modifier = Modifier, title: String = "Não foi possível concluir", onRetry: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        IconBubble(Icons.Outlined.ErrorOutline, LicitaColors.Red, size = 72.dp)
        Spacer(Modifier.height(18.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = LicitaColors.TextPrimary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextSecondary, textAlign = TextAlign.Center)
        if (onRetry != null) {
            Spacer(Modifier.height(20.dp))
            SecondaryButton("Tentar novamente", onRetry)
        }
    }
}

/** Bloco com efeito shimmer para skeleton loading. */
@Composable
fun SkeletonBox(modifier: Modifier = Modifier, height: Dp = 16.dp, cornerRadius: Dp = 8.dp) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val x by transition.animateFloat(
        initialValue = -400f, targetValue = 1200f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "x",
    )
    val brush = Brush.linearGradient(
        colors = listOf(LicitaColors.SurfaceElevated, LicitaColors.SurfaceHigh, LicitaColors.SurfaceElevated),
        start = Offset(x, 0f), end = Offset(x + 400f, 0f),
    )
    Box(modifier.height(height).clip(RoundedCornerShape(cornerRadius)).background(brush))
}

/** Lista de cards-esqueleto para telas de listagem em carregamento. */
@Composable
fun SkeletonList(modifier: Modifier = Modifier, items: Int = 4) {
    Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(items) {
            LicitaCard(Modifier.fillMaxWidth()) {
                SkeletonBox(Modifier.fillMaxWidth(0.55f), height = 18.dp)
                Spacer(Modifier.height(10.dp))
                SkeletonBox(Modifier.fillMaxWidth(), height = 12.dp)
                Spacer(Modifier.height(6.dp))
                SkeletonBox(Modifier.fillMaxWidth(0.8f), height = 12.dp)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SkeletonBox(Modifier.width(70.dp), height = 22.dp, cornerRadius = 50.dp)
                    SkeletonBox(Modifier.width(90.dp), height = 22.dp, cornerRadius = 50.dp)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Alertas / diálogos

@Composable
fun AlertBanner(
    title: String,
    message: String,
    tone: Tone,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    pulsing: Boolean = false,
) {
    val color = tone.color()
    Row(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.45f), MaterialTheme.shapes.large)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (pulsing) PulsingDot(color, size = 12.dp) else Icon(tone.icon(), contentDescription = null, tint = color)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary)
            if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
        }
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionLabel, color = color, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String = "Confirmar",
    dismissLabel: String = "Cancelar",
    tone: Tone = Tone.INFO,
    icon: ImageVector? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = icon?.let { { Icon(it, contentDescription = null, tint = tone.color()) } },
        title = { Text(title) },
        text = { Text(message, color = LicitaColors.TextSecondary) },
        confirmButton = {
            Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(containerColor = tone.color())) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
        containerColor = LicitaColors.SurfaceElevated,
    )
}

/**
 * Confirmação DUPLA para ações vinculantes (envio de proposta, resposta ao pregoeiro, lance):
 * mostra o resumo ([details]) e só habilita o botão após o usuário marcar a caixa de ciência.
 */
@Composable
fun BindingConfirmDialog(
    title: String,
    details: List<Pair<String, String>>,
    acknowledgeText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String = "Confirmar envio",
    simulationNote: String? = "Modo SIMULAÇÃO: nenhum dado será enviado ao portal.",
) {
    var acknowledged by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = LicitaColors.Yellow) },
        title = { Text(title) },
        text = {
            Column {
                details.forEach { (label, value) -> InfoRow(label, value) }
                if (simulationNote != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(simulationNote, style = MaterialTheme.typography.bodySmall, color = LicitaColors.BlueBright)
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { acknowledged = !acknowledged },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it })
                    Text(acknowledgeText, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextPrimary)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm, enabled = acknowledged,
                colors = ButtonDefaults.buttonColors(containerColor = LicitaColors.Green),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
        containerColor = LicitaColors.SurfaceElevated,
    )
}

/** Linha com ações lado a lado ocupando a largura. */
@Composable
fun ButtonRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, content = content)
}
