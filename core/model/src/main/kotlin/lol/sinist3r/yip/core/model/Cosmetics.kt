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
    /** Picker section for Twitch emotes: the owning channel's name, an event (Hype Train, Prime...) or Twitch. */
    val group: String? = null,
) {
    fun url(scale: Int): String = urls[(scale - 1).coerceIn(0, urls.lastIndex)]

    val favouriteKey: String get() = "${provider.name}:$id"
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

/** Picker sections for Twitch emotes that don't belong to a channel. */
object TwitchEmoteGroups {
    const val TWITCH = "Twitch"
    private val byType = mapOf(
        "hypetrain" to "Hype Train",
        "prime" to "Prime Gaming",
        "turbo" to "Turbo",
        "limitedtime" to "Limited time",
        "rewards" to "Rewards",
        "twofactor" to "Two-factor",
        "smilies" to "Smilies",
        "globals" to TWITCH,
    )
    val events: Set<String> = byType.values.toSet() - TWITCH

    /** What unlocks one of a channel's own emotes: "Tier 1/2/3", "Bits" or "Follower". */
    fun unlockLabel(emoteType: String?, tier: String?): String = when (emoteType) {
        "bitstier" -> "Bits"
        "follower" -> "Follower"
        else -> when (tier) { "2000" -> "Tier 2"; "3000" -> "Tier 3"; else -> "Tier 1" }
    }

    /** Event emotes go by type; channel emotes by owner; the rest are Twitch's. */
    fun of(emoteType: String?, ownerName: String?): String =
        byType[emoteType] ?: ownerName?.takeIf { it.isNotBlank() } ?: TWITCH
}
