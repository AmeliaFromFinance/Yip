package lol.sinist3r.yip.core.designsystem.chat

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TileMode
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import lol.sinist3r.yip.core.model.Paint
import lol.sinist3r.yip.core.model.PaintFunction

/**
 * Builds a Compose brush for a 7TV paint spanning [left, left+width] x [top, top+height] in
 * text-layout pixels. CSS angles: 0deg points up, 90deg points right.
 */
fun Paint.toBrush(left: Float, top: Float, width: Float, height: Float): Brush {
    val colors = stops.map { Color(it.argb) }
    if (colors.size < 2 || function == PaintFunction.URL && stops.isEmpty()) {
        return SolidColor(Color(fallbackArgb ?: stops.firstOrNull()?.argb ?: 0xFFFFFFFF))
    }
    val positions = stops.map { it.at.coerceIn(0f, 1f) }
    val tile = if (repeat) TileMode.Repeated else TileMode.Clamp
    return when (function) {
        PaintFunction.RADIAL_GRADIENT -> Brush.radialGradient(
            colorStops = positions.zip(colors).toTypedArray(),
            center = Offset(left + width / 2, top + height / 2),
            radius = maxOf(width, height) / 2f,
            tileMode = tile,
        )
        else -> {
            val rad = Math.toRadians(angle.toDouble())
            val dx = sin(rad).toFloat()
            val dy = -cos(rad).toFloat()
            val len = abs(width * dx) + abs(height * dy)
            val cx = left + width / 2
            val cy = top + height / 2
            Brush.linearGradient(
                colorStops = positions.zip(colors).toTypedArray(),
                start = Offset(cx - dx * len / 2, cy - dy * len / 2),
                end = Offset(cx + dx * len / 2, cy + dy * len / 2),
                tileMode = tile,
            )
        }
    }
}

/** 7TV paints can carry several drop shadows; Compose text takes one, so use the strongest. */
fun Paint.primaryShadow(scale: Float): Shadow? = shadows.maxByOrNull { it.radius }?.let {
    Shadow(Color(it.argb), Offset(it.x * scale, it.y * scale), it.radius * scale)
}

/** Lighten or darken a user's name colour so it stays readable on the current background. */
fun readableColor(argb: Long?, dark: Boolean, fallback: Color): Color {
    val c = argb?.let { Color(it) } ?: return fallback
    val hsl = FloatArray(3)
    androidx.core.graphics.ColorUtils.colorToHSL(android.graphics.Color.argb(255, (c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt()), hsl)
    if (dark && hsl[2] < 0.5f) hsl[2] = 0.5f + (hsl[2] * 0.3f)
    if (!dark && hsl[2] > 0.5f) hsl[2] = 0.35f + (hsl[2] - 0.5f) * 0.3f
    return Color(androidx.core.graphics.ColorUtils.HSLToColor(hsl))
}
