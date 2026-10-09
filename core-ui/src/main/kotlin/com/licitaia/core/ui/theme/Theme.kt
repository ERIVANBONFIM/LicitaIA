package com.licitaia.core.ui.theme

import android.app.Activity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * Paleta do LicitaIA. Modo LOCAL: dark, com azul (ação) e verde (sucesso).
 * Modo PLATAFORMA (modelo B, o site dentro do app): [claro] = true → as telas do celular (IA, Portais, Compras.gov,
 * robôs) usam as MESMAS cores do site (fundo claro, verde #00874a), para não ficar cor diferente do site.
 */
object LicitaColors {
    /** true = padrão do site (claro). Trocado pelo app ao entrar/sair do modo plataforma. */
    var claro by mutableStateOf(false)

    private fun c(escuro: Long, claroHex: Long) = if (claro) Color(claroHex) else Color(escuro)

    val Background: Color get() = c(0xFF070B14, 0xFFF5F7F6)
    val Surface: Color get() = c(0xFF0F1626, 0xFFFFFFFF)
    val SurfaceElevated: Color get() = c(0xFF151E33, 0xFFFAFBFA)
    val SurfaceHigh: Color get() = c(0xFF1C2742, 0xFFEEF2F0)
    val Outline: Color get() = c(0xFF243150, 0xFFD7DCD9)
    val OutlineSoft: Color get() = c(0xFF1A2440, 0xFFE5E7EB)

    val Blue: Color get() = c(0xFF3B82F6, 0xFF00874A)
    val BlueBright: Color get() = c(0xFF60A5FA, 0xFF066B3D)
    val Green: Color get() = c(0xFF10B981, 0xFF16A34A)
    val GreenBright: Color get() = c(0xFF34D399, 0xFF15803D)
    val Yellow: Color get() = c(0xFFF59E0B, 0xFFC2691A)
    val Red: Color get() = c(0xFFEF4444, 0xFFDC2626)
    val RedBright: Color get() = c(0xFFF87171, 0xFFB91C1C)
    val Purple: Color get() = c(0xFF8B5CF6, 0xFF6D28D9)
    val Cyan: Color get() = c(0xFF22D3EE, 0xFF0E7490)

    val TextPrimary: Color get() = c(0xFFE8EEF9, 0xFF1A1A1A)
    val TextSecondary: Color get() = c(0xFF9AA8C1, 0xFF4B5563)
    val TextMuted: Color get() = c(0xFF64748B, 0xFF6B7280)

    val BrandGradient: Brush get() = Brush.linearGradient(listOf(Blue, Green))
    val HeroGradient: Brush get() =
        if (claro) Brush.linearGradient(listOf(Color(0xFFE0F0E7), Color(0xFFF2F9F5)))
        else Brush.linearGradient(listOf(Color(0xFF12224A), Color(0xFF0C2A2B)))
    val DangerGradient: Brush get() = Brush.linearGradient(listOf(Color(0xFFB91C1C), Color(0xFFEF4444)))
}

private fun escuro(): ColorScheme = darkColorScheme(
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

/** Mesmas cores do site (main.css): verde #00874a, fundo #f5f7f6, cartões brancos, bordas #d7dcd9. */
private fun claro(): ColorScheme = lightColorScheme(
    primary = LicitaColors.Blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0F0E7),
    onPrimaryContainer = Color(0xFF066B3D),
    secondary = LicitaColors.Green,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0F0E7),
    onSecondaryContainer = Color(0xFF066B3D),
    tertiary = LicitaColors.Yellow,
    onTertiary = Color.White,
    error = LicitaColors.Red,
    onError = Color.White,
    errorContainer = Color(0xFFFEF2F2),
    onErrorContainer = Color(0xFFB91C1C),
    background = LicitaColors.Background,
    onBackground = LicitaColors.TextPrimary,
    surface = LicitaColors.Surface,
    onSurface = LicitaColors.TextPrimary,
    surfaceVariant = LicitaColors.SurfaceElevated,
    onSurfaceVariant = LicitaColors.TextSecondary,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = LicitaColors.Surface,
    surfaceContainer = LicitaColors.Surface,
    surfaceContainerHigh = LicitaColors.SurfaceElevated,
    surfaceContainerHighest = LicitaColors.SurfaceHigh,
    outline = LicitaColors.Outline,
    outlineVariant = LicitaColors.OutlineSoft,
    scrim = Color(0x66000000),
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

/** Dark no modo local; padrão do site (claro) no modo plataforma ([LicitaColors.claro]). */
@Composable
fun LicitaTheme(content: @Composable () -> Unit) {
    val claro = LicitaColors.claro
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = claro      // ícones escuros na barra de status clara
                isAppearanceLightNavigationBars = claro
            }
        }
    }
    MaterialTheme(
        colorScheme = if (claro) claro() else escuro(),
        typography = LicitaTypography,
        shapes = LicitaShapes,
        content = content,
    )
}
