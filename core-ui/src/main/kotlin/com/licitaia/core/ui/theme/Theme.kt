package com.licitaia.core.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Paleta premium do LicitaIA — dark, com azul (ação) e verde (sucesso) como destaques. */
object LicitaColors {
    val Background = Color(0xFF070B14)
    val Surface = Color(0xFF0F1626)
    val SurfaceElevated = Color(0xFF151E33)
    val SurfaceHigh = Color(0xFF1C2742)
    val Outline = Color(0xFF243150)
    val OutlineSoft = Color(0xFF1A2440)

    val Blue = Color(0xFF3B82F6)
    val BlueBright = Color(0xFF60A5FA)
    val Green = Color(0xFF10B981)
    val GreenBright = Color(0xFF34D399)
    val Yellow = Color(0xFFF59E0B)
    val Red = Color(0xFFEF4444)
    val RedBright = Color(0xFFF87171)
    val Purple = Color(0xFF8B5CF6)
    val Cyan = Color(0xFF22D3EE)

    val TextPrimary = Color(0xFFE8EEF9)
    val TextSecondary = Color(0xFF9AA8C1)
    val TextMuted = Color(0xFF64748B)

    val BrandGradient = Brush.linearGradient(listOf(Blue, Green))
    val HeroGradient = Brush.linearGradient(listOf(Color(0xFF12224A), Color(0xFF0C2A2B)))
    val DangerGradient = Brush.linearGradient(listOf(Color(0xFFB91C1C), Color(0xFFEF4444)))
}

private val ColorScheme = darkColorScheme(
    primary = LicitaColors.Blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF16305F),
    onPrimaryContainer = Color(0xFFD6E4FF),
    secondary = LicitaColors.Green,
    onSecondary = Color(0xFF00281C),
    secondaryContainer = Color(0xFF0C3B2E),
    onSecondaryContainer = Color(0xFFBFF3DF),
    tertiary = LicitaColors.Yellow,
    onTertiary = Color(0xFF2B1B00),
    error = LicitaColors.Red,
    onError = Color.White,
    errorContainer = Color(0xFF4A1414),
    onErrorContainer = Color(0xFFFFD9D9),
    background = LicitaColors.Background,
    onBackground = LicitaColors.TextPrimary,
    surface = LicitaColors.Surface,
    onSurface = LicitaColors.TextPrimary,
    surfaceVariant = LicitaColors.SurfaceElevated,
    onSurfaceVariant = LicitaColors.TextSecondary,
    surfaceContainerLowest = LicitaColors.Background,
    surfaceContainerLow = LicitaColors.Surface,
    surfaceContainer = LicitaColors.Surface,
    surfaceContainerHigh = LicitaColors.SurfaceElevated,
    surfaceContainerHighest = LicitaColors.SurfaceHigh,
    outline = LicitaColors.Outline,
    outlineVariant = LicitaColors.OutlineSoft,
    scrim = Color(0xCC000000),
)

private val Sans = FontFamily.SansSerif

private val LicitaTypography = Typography(
    displaySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.5).sp),
    headlineLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.4).sp),
    headlineMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.4.sp),
)

private val LicitaShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(26.dp),
)

/** O LicitaIA é dark-only por direção de produto. */
@Composable
fun LicitaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ColorScheme,
        typography = LicitaTypography,
        shapes = LicitaShapes,
        content = content,
    )
}
