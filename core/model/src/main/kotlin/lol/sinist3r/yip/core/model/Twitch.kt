package lol.sinist3r.yip.core.model

import kotlin.time.Instant
import kotlinx.serialization.Serializable

@Serializable
data class User(
    val id: String,
    val login: String,
    val displayName: String,
    val profileImageUrl: String? = null,
    val description: String? = null,
    val broadcasterType: String? = null,
    val createdAt: Instant? = null,
) {
    val isPartner get() = broadcasterType == "partner"
}

@Serializable
data class LiveStream(
    val id: String,
    val userId: String,
    val userLogin: String,
    val userName: String,
    val gameId: String?,
    val gameName: String?,
    val title: String,
    val viewerCount: Int,
    val startedAt: Instant,
    val language: String?,
    val thumbnailTemplate: String,
    val tags: List<String> = emptyList(),
    val isMature: Boolean = false,
    val profileImageUrl: String? = null,
    /** Twitch's own rerun flag; also guessed from a "Rerun" tag or title. */
    val streamType: String? = null,
) {
    val isRerun: Boolean
        get() = streamType.equals("rerun", true) || tags.any { it.equals("rerun", true) } ||
            title.trimStart().startsWith("[rerun", true) || title.trimStart().startsWith("rerun:", true)

    fun thumbnail(width: Int = 640, height: Int = 360): String =
        thumbnailTemplate.replace("{width}", width.toString()).replace("{height}", height.toString())
}

@Serializable
data class Category(
    val id: String,
    val name: String,
    val boxArtTemplate: String,
    val viewerCount: Int? = null,
) {
    fun boxArt(width: Int = 285, height: Int = 380): String =
        boxArtTemplate.replace("{width}", width.toString()).replace("{height}", height.toString())
}

@Serializable
data class Channel(
    val id: String,
    val login: String,
    val displayName: String,
    val gameName: String? = null,
    val title: String? = null,
    val isLive: Boolean = false,
    val profileImageUrl: String? = null,
    val thumbnailUrl: String? = null,
)

@Serializable
data class FollowedChannel(
    val id: String,
    val login: String,
    val displayName: String,
    val followedAt: Instant?,
    val profileImageUrl: String? = null,
)

enum class VideoType { ARCHIVE, HIGHLIGHT, UPLOAD }

@Serializable
data class Video(
    val id: String,
    val userId: String,
    val userLogin: String,
    val userName: String,
    val title: String,
    val createdAt: Instant,
    val durationSeconds: Long,
    val viewCount: Long,
    val thumbnailTemplate: String,
    val type: VideoType,
) {
    fun thumbnail(width: Int = 640, height: Int = 360): String =
        thumbnailTemplate.replace("%{width}", width.toString()).replace("%{height}", height.toString())
            .replace("{width}", width.toString()).replace("{height}", height.toString())
}

@Serializable
data class Clip(
    val id: String,
    val url: String,
    val broadcasterId: String,
    val broadcasterName: String,
    val creatorName: String,
    val title: String,
    val viewCount: Long,
    val createdAt: Instant,
    val thumbnailUrl: String,
    val durationSeconds: Double,
    val videoId: String? = null,
    val vodOffset: Int? = null,
    val gameId: String? = null,
)

@Serializable
data class ScheduleSegment(
    val id: String,
    val title: String,
    val startTime: Instant,
    val endTime: Instant?,
    val categoryName: String?,
    val isRecurring: Boolean,
    val canceled: Boolean,
)

data class Page<T>(val items: List<T>, val cursor: String?)

enum class RecentKind { CHANNEL, CATEGORY, QUERY }

/** Something opened or searched from the search screen. [key] is a login, category id or query. */
@Serializable
data class RecentSearch(
    val kind: RecentKind,
    val key: String,
    val label: String,
    val imageUrl: String? = null,
)

@Serializable
data class RecentSearches(val items: List<RecentSearch> = emptyList())
