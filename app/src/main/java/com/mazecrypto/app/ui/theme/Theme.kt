package com.mazecrypto.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.mazecrypto.app.R
import kotlin.math.abs

/**
 * The Maze design tokens (desktop ui/theme.py): OLED black, near-black glass
 * cards with a lit rim, Inter, one accent colour — champagne by default.
 * Green and red only ever mean up/down or profit/loss; orange means
 * "needs attention".
 */
@Immutable
data class Palette(
    val accent: Color,
    val accentHi: Color,
    val accentBg: Color,
    val onAccent: Color,
    val glow: Color,
    val glow2: Color,
    val violet: Color,
) {
    val bg = Color(0xFF000000)
    val base = Color(0xFF050506)
    val surface = Color(0xFF0E0E11)
    val raised = Color(0xFF16161A)
    val border = Color(0xFF232329)
    val border2 = Color(0xFF34343C)
    val text = Color(0xFFEDEDF0)
    val textMid = Color(0xFFA1A1AA)
    val muted = Color(0xFF71717A)
    val dim = Color(0xFF3F3F46)
    val teal = Color(0xFF2DD4BF)
    val up = Color(0xFF34D399)
    val down = Color(0xFFF87171)
    val warn = Color(0xFFFB923C)
    val info = Color(0xFF93C5FD)
    val glass = Color.White.copy(alpha = 0.045f)
    val glassHi = Color.White.copy(alpha = 0.075f)
    val glassLine = Color.White.copy(alpha = 0.09f)
    val glassLineHi = Color.White.copy(alpha = 0.16f)

    fun change(v: Double?): Color = when {
        v == null || abs(v) < 0.005 -> textMid
        v > 0 -> up
        else -> down
    }

    companion object {
        // Each avoids the colours that already carry meaning (green, red, orange).
        fun of(name: String): Palette = when (name) {
            "mono" -> Palette(Color(0xFFEDEDF0), Color(0xFFFFFFFF), Color(0xFF232328), Color(0xFF0A0A0B), Color.White, Color.White, Color(0xFFA78BFA))
            "gold" -> Palette(Color(0xFFF5B841), Color(0xFFFFD27A), Color(0xFF2A2010), Color(0xFF1A1200), Color(0xFFF5B841), Color(0xFFA78BFA), Color(0xFFA78BFA))
            "violet" -> Palette(Color(0xFFA78BFA), Color(0xFFC4B5FD), Color(0xFF1F1A33), Color(0xFF140F24), Color(0xFFA78BFA), Color(0xFFF5B841), Color(0xFFF5B841))
            else -> Palette(Color(0xFFE9D8B4), Color(0xFFF6EAD0), Color(0xFF29241A), Color(0xFF17130A), Color(0xFFE9D8B4), Color.White, Color(0xFFA78BFA))
        }
    }
}

val LocalPalette = staticCompositionLocalOf { Palette.of("champagne") }

@OptIn(ExperimentalTextApi::class)
private fun inter(weight: Int) = Font(
    R.font.inter, FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Inter = FontFamily(inter(400), inter(500), inter(600), inter(700))

/** The type ramp, sentence case throughout. `tnum` keeps columns of numbers aligned. */
object Type {
    val pageTitle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, letterSpacing = (-0.3).sp)
    val pageSub = TextStyle(fontFamily = Inter, fontSize = 13.5.sp, lineHeight = 18.sp)
    val section = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 1.1.sp)
    val cardTitle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 12.5.sp)
    val big = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 30.sp, fontFeatureSettings = "tnum", letterSpacing = (-0.5).sp)
    val value = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, fontFeatureSettings = "tnum")
    val body = TextStyle(fontFamily = Inter, fontSize = 14.sp, lineHeight = 20.sp)
    val bodySmall = TextStyle(fontFamily = Inter, fontSize = 12.5.sp, lineHeight = 17.sp)
    val strong = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp)
    val label = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 13.sp)
    val num = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 14.sp, fontFeatureSettings = "tnum")
    val numSmall = TextStyle(fontFamily = Inter, fontSize = 12.5.sp, fontFeatureSettings = "tnum")
    val small = TextStyle(fontFamily = Inter, fontSize = 11.5.sp, lineHeight = 15.sp)
    val button = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
}

@Composable
fun MazeTheme(accent: String, content: @Composable () -> Unit) {
    val p = Palette.of(accent)
    val scheme = darkColorScheme(
        primary = p.accent, onPrimary = p.onAccent, primaryContainer = p.accentBg, onPrimaryContainer = p.accentHi,
        secondary = p.teal, background = p.bg, onBackground = p.text, surface = p.surface, onSurface = p.text,
        surfaceVariant = p.raised, onSurfaceVariant = p.textMid, outline = p.border2, outlineVariant = p.border,
        error = p.down, surfaceContainer = p.surface, surfaceContainerHigh = p.raised, surfaceContainerHighest = p.raised,
        surfaceContainerLow = p.base, surfaceContainerLowest = p.bg,
    )
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography.let { t ->
            t.copy(
                bodyLarge = t.bodyLarge.copy(fontFamily = Inter), bodyMedium = t.bodyMedium.copy(fontFamily = Inter),
                bodySmall = t.bodySmall.copy(fontFamily = Inter), labelLarge = t.labelLarge.copy(fontFamily = Inter),
                labelMedium = t.labelMedium.copy(fontFamily = Inter), labelSmall = t.labelSmall.copy(fontFamily = Inter),
                titleLarge = t.titleLarge.copy(fontFamily = Inter), titleMedium = t.titleMedium.copy(fontFamily = Inter),
                titleSmall = t.titleSmall.copy(fontFamily = Inter), headlineSmall = t.headlineSmall.copy(fontFamily = Inter),
            )
        }, content = content)
    }
}
