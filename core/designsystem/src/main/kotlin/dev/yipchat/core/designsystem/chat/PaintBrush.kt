package dev.yipchat.core.designsystem.chat

import android.graphics.Matrix
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TileMode
import androidx.core.graphics.ColorUtils
import dev.yipchat.core.model.Paint
import dev.yipchat.core.model.PaintFunction
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Builds a Compose brush for a 7TV paint spanning [left, left+width] x [top, top+height] in
 * text-layout pixels. CSS angles: 0deg points up, 90deg points right.
 */
fun Paint.toBrush(left: Float, top: Float, width: Float, height: Float, image: ImageBitmap? = null): Brush {
    if (function == PaintFunction.URL && image != null) return imageBrush(image, left, top, width, height)
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

/** Scales the paint image to cover the name box and repeats it sideways for long names. */
private fun imageBrush(image: ImageBitmap, left: Float, top: Float, width: Float, height: Float): Brush = object : ShaderBrush() {
    override fun createShader(size: Size): Shader = ImageShader(image, TileMode.Repeated, TileMode.Clamp).apply {
        val scale = maxOf(height / image.height, width / image.width)
        setLocalMatrix(Matrix().apply { setScale(scale, scale); postTranslate(left, top) })
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
    ColorUtils.colorToHSL(android.graphics.Color.argb(255, (c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt()), hsl)
    if (dark && hsl[2] < 0.5f) hsl[2] = 0.5f + (hsl[2] * 0.3f)
    if (!dark && hsl[2] > 0.5f) hsl[2] = 0.35f + (hsl[2] - 0.5f) * 0.3f
    return Color(ColorUtils.HSLToColor(hsl))
}

/** Nudges lightness until [fg] reaches [minRatio] contrast on [bg] (WCAG AA for text is 4.5). */
fun withContrast(fg: Color, bg: Color, minRatio: Double = 4.5): Color {
    fun argb(c: Color) = android.graphics.Color.argb(255, (c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt())
    val back = argb(bg)
    if (ColorUtils.calculateContrast(argb(fg), back) >= minRatio) return fg
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(argb(fg), hsl)
    val lighten = ColorUtils.calculateLuminance(back) < 0.5
    repeat(20) {
        hsl[2] = (hsl[2] + if (lighten) 0.05f else -0.05f).coerceIn(0f, 1f)
        val c = ColorUtils.HSLToColor(hsl)
        if (ColorUtils.calculateContrast(c, back) >= minRatio) return Color(c)
    }
    return Color(ColorUtils.HSLToColor(hsl))
}
