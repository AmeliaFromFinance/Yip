package lol.sinist3r.yip.core.model

import kotlinx.serialization.Serializable

enum class EmoteProvider { TWITCH, SEVENTV, BTTV, FFZ }

@Serializable
data class Emote(
    val id: String,
    val code: String,
    val provider: EmoteProvider,
    /** URLs for 1x/2x/3x/4x where available, smallest first. */
    val urls: List<String>,
    val animated: Boolean = false,
    val zeroWidth: Boolean = false,
    val ownerName: String? = null,
    val isChannel: Boolean = false,
    val isPersonal: Boolean = false,
    val width: Int? = null,
    val height: Int? = null,
) {
    fun url(scale: Int): String = urls[(scale - 1).coerceIn(0, urls.lastIndex)]
}

enum class BadgeSource { TWITCH, SEVENTV, BTTV, FFZ, FFZAP, CHATTERINO, HOMIES, HOMIES_CUSTOM, MOLTORINO }

@Serializable
data class Badge(
    val id: String,
    val title: String,
    val urls: List<String>,
    val source: BadgeSource,
    val backgroundColor: String? = null,
)

enum class PaintFunction { LINEAR_GRADIENT, RADIAL_GRADIENT, URL }

@Serializable
data class PaintStop(val at: Float, val argb: Long)

@Serializable
data class PaintShadow(val x: Float, val y: Float, val radius: Float, val argb: Long)

@Serializable
data class Paint(
    val id: String,
    val name: String,
    val function: PaintFunction,
    val angle: Float = 90f,
    val repeat: Boolean = false,
    val stops: List<PaintStop> = emptyList(),
    val imageUrl: String? = null,
    /** Animated WebP for image paints that move; [imageUrl] stays the static frame. */
    val animatedImageUrl: String? = null,
    val shadows: List<PaintShadow> = emptyList(),
    val fallbackArgb: Long? = null,
)
