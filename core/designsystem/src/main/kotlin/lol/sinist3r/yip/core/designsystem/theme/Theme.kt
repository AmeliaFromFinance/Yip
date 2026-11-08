package lol.sinist3r.yip.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import lol.sinist3r.yip.core.model.AccentColor
import lol.sinist3r.yip.core.model.AppearanceSettings
import lol.sinist3r.yip.core.model.ThemeMode

/** Seed colours for each accent. FOX is the default warm amber. */
val AccentColor.seed: Color
    get() = when (this) {
        AccentColor.FOX -> Color(0xFFF28C28)
        AccentColor.CYAN -> Color(0xFF4CC9F0)
        AccentColor.ROSE -> Color(0xFFFF6FA8)
        AccentColor.VIOLET -> Color(0xFF9B7BFF)
        AccentColor.MINT -> Color(0xFF3DD9A4)
        AccentColor.SKY -> Color(0xFF4F8DFD)
        AccentColor.CRIMSON -> Color(0xFFE5484D)
        AccentColor.DYNAMIC -> Color(0xFFF28C28)
    }

@Immutable
data class YipExtras(
    val isDark: Boolean,
    val live: Color = Color(0xFFFF4D5E),
    val mention: Color,
    val surfaceRaised: Color,
    val divider: Color,
    val cornerRadius: Int,
    val reduceMotion: Boolean,
)

val LocalYipExtras = staticCompositionLocalOf {
    YipExtras(isDark = true, mention = Color(0x33F28C28), surfaceRaised = Color(0xFF15181D), divider = Color(0xFF232830), cornerRadius = 12, reduceMotion = false)
}

private fun schemeFor(seed: Color, dark: Boolean, black: Boolean): ColorScheme {
    val onSeed = if (seed.luminance() > 0.45f) Color(0xFF1A1004) else Color.White
    return if (dark) {
        val bg = if (black) Color.Black else Color(0xFF0E0D0C)
        val surface = if (black) Color(0xFF0A0A0A) else Color(0xFF151311)
        darkColorScheme(
            primary = seed,
            onPrimary = onSeed,
            primaryContainer = lerp(seed, bg, 0.72f),
            onPrimaryContainer = lerp(seed, Color.White, 0.6f),
            secondary = lerp(seed, Color(0xFFE6D3C2), 0.55f),
            onSecondary = Color(0xFF1A1004),
            secondaryContainer = lerp(seed, surface, 0.82f),
            onSecondaryContainer = Color(0xFFF2E6DA),
            tertiary = Color(0xFF7FD1C2),
            background = bg,
            onBackground = Color(0xFFEDE8E3),
            surface = bg,
            onSurface = Color(0xFFEDE8E3),
            surfaceVariant = Color(0xFF26221F),
            onSurfaceVariant = Color(0xFFB7AEA6),
            surfaceContainerLowest = bg,
            surfaceContainerLow = surface,
            surfaceContainer = if (black) Color(0xFF111111) else Color(0xFF1B1916),
            surfaceContainerHigh = if (black) Color(0xFF181818) else Color(0xFF23201D),
            surfaceContainerHighest = if (black) Color(0xFF202020) else Color(0xFF2C2825),
            outline = Color(0xFF5B534D),
            outlineVariant = Color(0xFF34302C),
            error = Color(0xFFFF6B6B),
        )
    } else {
        lightColorScheme(
            primary = lerp(seed, Color.Black, 0.18f),
            onPrimary = Color.White,
            primaryContainer = lerp(seed, Color.White, 0.75f),
            onPrimaryContainer = lerp(seed, Color.Black, 0.6f),
            secondary = lerp(seed, Color(0xFF5A4636), 0.6f),
            secondaryContainer = lerp(seed, Color.White, 0.85f),
            background = Color(0xFFFFFBF8),
            onBackground = Color(0xFF1F1A16),
            surface = Color(0xFFFFFBF8),
            onSurface = Color(0xFF1F1A16),
            surfaceVariant = Color(0xFFF2E9E1),
            onSurfaceVariant = Color(0xFF5B5049),
            surfaceContainerLow = Color(0xFFFBF3EC),
            surfaceContainer = Color(0xFFF6EDE5),
            surfaceContainerHigh = Color(0xFFF0E6DD),
            surfaceContainerHighest = Color(0xFFEADFD5),
            outline = Color(0xFF8D8077),
            outlineVariant = Color(0xFFD9CCC2),
        )
    }
}

@Composable
fun YipTheme(appearance: AppearanceSettings = AppearanceSettings(), content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (appearance.theme) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.BLACK -> true
    }
    val black = appearance.theme == ThemeMode.BLACK
    val context = LocalContext.current
    val seed = appearance.customAccentArgb?.let { Color(it) } ?: appearance.accent.seed
    val scheme = if (appearance.accent == AccentColor.DYNAMIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val base = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        if (black) base.copy(background = Color.Black, surface = Color.Black, surfaceContainerLowest = Color.Black) else base
    } else {
        schemeFor(seed, dark, black)
    }
    val radius = appearance.roundedCorners.coerceIn(0, 28)
    val extras = YipExtras(
        isDark = dark,
        mention = scheme.primary.copy(alpha = 0.18f),
        surfaceRaised = scheme.surfaceContainerHigh,
        divider = scheme.outlineVariant,
        cornerRadius = radius,
        reduceMotion = appearance.reduceMotion,
    )
    CompositionLocalProvider(LocalYipExtras provides extras) {
        MaterialTheme(
            colorScheme = scheme,
            typography = YipTypography,
            shapes = Shapes(
                extraSmall = RoundedCornerShape((radius / 3).dp),
                small = RoundedCornerShape((radius * 2 / 3).dp),
                medium = RoundedCornerShape(radius.dp),
                large = RoundedCornerShape((radius * 4 / 3).dp),
                extraLarge = RoundedCornerShape((radius * 2).dp),
            ),
            content = content,
        )
    }
}

object YipTheme {
    val extras: YipExtras @Composable get() = LocalYipExtras.current
}
