package dev.soulacy.android.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.soulacy.android.R

/**
 * Soulacy's palette, value for value from the iOS app's Theme.swift
 * (SoulacyUI): a sea accent on a navy-to-teal field, the same tokens as the
 * web GUI. System Material roles still carry accessibility work.
 */
@Immutable
data class SoulacyColors(
    val brand: Color,
    val brandDeep: Color,
    val brandSecondary: Color,
    val canvas: Color,
    val canvasMid: Color,
    val canvasEnd: Color,
    val surface: Color,
    val raised: Color,
    val row: Color,
    val success: Color,
    val dark: Boolean,
) {
    val coral = Color(0xFFFF5C73)
    val mango = Color(0xFFFFB021)
    val leaf = Color(0xFF38B56B)
    val canvasGradient: Brush get() = Brush.verticalGradient(listOf(canvas, canvasMid, canvasEnd))
    val brandGradient: Brush get() = Brush.linearGradient(listOf(brand, brandDeep))
}

private val Dark = SoulacyColors(
    brand = Color(0xFF3FD1DB),
    brandDeep = Color(0xFF1C788A),
    brandSecondary = Color(0xFF4DC79E),
    canvas = Color(0xFF0B1B2B),
    canvasMid = Color(0xFF123B52),
    canvasEnd = Color(0xFF1C596E),
    surface = Color(0xFF10233A),
    raised = Color(0xFF163049),
    row = Color.White.copy(alpha = 0.07f),
    success = Color(0xFF33E08C),
    dark = true,
)

private val Light = SoulacyColors(
    brand = Color(0xFF0FB5A5),
    brandDeep = Color(0xFF087870),
    brandSecondary = Color(0xFF0A8F75),
    canvas = Color.White,
    canvasMid = Color(0xFFF9FCFB),
    canvasEnd = Color(0xFFEDF9F6),
    surface = Color(0xFFF6FBF9),
    raised = Color.White,
    row = Color.White,
    success = Color(0xFF009157),
    dark = false,
)

val LocalSoulacy = staticCompositionLocalOf { Dark }

object Soulacy {
    val colors: SoulacyColors @Composable get() = LocalSoulacy.current

    /** The logotype face: Grand Hotel, used for the wordmark and nowhere else (as on iOS). */
    val wordmark = FontFamily(Font(R.font.grand_hotel))

    /** Same hash as iOS Theme.hue(for:) and the web Feed, so an agent's colour matches everywhere. */
    fun hue(id: String): Float {
        var h = 0
        for (c in id) h = (h * 31 + c.code) % 360
        return h.toFloat()
    }

    fun avatarBrush(id: String): Brush {
        val h = hue(id)
        return Brush.linearGradient(
            listOf(Color.hsv(h, 0.70f, 0.72f), Color.hsv((h + 30f) % 360f, 0.80f, 0.95f)),
        )
    }
}

@Composable
fun SoulacyTheme(appearance: dev.soulacy.android.data.Appearance = dev.soulacy.android.data.Appearance.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (appearance) {
        dev.soulacy.android.data.Appearance.SYSTEM -> isSystemInDarkTheme()
        dev.soulacy.android.data.Appearance.LIGHT -> false
        dev.soulacy.android.data.Appearance.DARK -> true
    }
    val c = if (dark) Dark else Light
    val scheme = if (c.dark) {
        darkColorScheme(
            primary = c.brand, onPrimary = Color(0xFF00242A), primaryContainer = c.brandDeep, onPrimaryContainer = Color.White,
            secondary = c.brandSecondary, background = c.canvas, onBackground = Color(0xFFE8F1F5),
            surface = c.surface, onSurface = Color(0xFFE8F1F5), surfaceVariant = c.raised, onSurfaceVariant = Color(0xFFA9BBC8),
            surfaceContainer = c.surface, surfaceContainerHigh = c.raised, surfaceContainerLow = c.surface,
            secondaryContainer = c.raised, onSecondaryContainer = Color(0xFFE8F1F5),
            outline = Color(0xFF3A5468), outlineVariant = Color(0xFF22394D), error = c.coral,
            errorContainer = Color(0xFF4A1A24), onErrorContainer = Color(0xFFFFD9DE),
        )
    } else {
        lightColorScheme(
            primary = c.brandDeep, onPrimary = Color.White, primaryContainer = Color(0xFFCFF3EE), onPrimaryContainer = Color(0xFF00201D),
            secondary = c.brandSecondary, background = c.canvas, surface = c.surface,
            surfaceVariant = Color(0xFFE6F2EF), surfaceContainer = c.surface, surfaceContainerHigh = Color(0xFFEAF5F2),
            secondaryContainer = Color(0xFFE2F4F0), error = Color(0xFFD02A43),
        )
    }
    CompositionLocalProvider(LocalSoulacy provides c) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** The field behind every screen: the canvas gradient and, at night, a faint dot grid (iOS SoulacyBackground). */
@Composable
fun SoulacyBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = Soulacy.colors
    Box(modifier.fillMaxSize().background(c.canvas).background(c.canvasGradient)) {
        if (c.dark) {
            Canvas(Modifier.fillMaxSize()) {
                val step = 24.dp.toPx()
                val r = 0.7.dp.toPx()
                var y = step / 2
                while (y < size.height * 0.6f) {
                    val alpha = 0.045f * (1f - y / (size.height * 0.6f))
                    var x = step / 2
                    while (x < size.width) {
                        drawCircle(Color.White.copy(alpha = alpha), r, Offset(x, y))
                        x += step
                    }
                    y += step
                }
            }
        }
        // Content drawn straight on the canvas (not inside a Surface) must
        // still read in dark mode; without this it falls back to black.
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) { content() }
    }
}
