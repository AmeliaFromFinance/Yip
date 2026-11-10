package dev.yipchat.core.network.twitch

import java.time.OffsetDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable data class HelixPage<T>(val data: List<T> = emptyList(), val pagination: Pagination? = null, val total: Int? = null)
@Serializable data class Pagination(val cursor: String? = null)

@Serializable data class HelixUser(
    val id: String, val login: String, val displayName: String, val profileImageUrl: String? = null,
    val description: String? = null, val broadcasterType: String? = null, val createdAt: String? = null,
)

@Serializable data class HelixStream(
    val id: String, val userId: String, val userLogin: String, val userName: String,
    val gameId: String? = null, val gameName: String? = null, val title: String = "",
    val viewerCount: Int = 0, val startedAt: String, val language: String? = null,
    val thumbnailUrl: String = "", val tags: List<String>? = null, val isMature: Boolean = false,
)

@Serializable data class HelixGame(val id: String, val name: String, val boxArtUrl: String = "")

@Serializable data class HelixSearchChannel(
    val id: String, val broadcasterLogin: String, val displayName: String, val gameName: String? = null,
    val title: String? = null, val isLive: Boolean = false, val thumbnailUrl: String? = null,
)

@Serializable data class HelixFollowed(
    val broadcasterId: String, val broadcasterLogin: String, val broadcasterName: String, val followedAt: String? = null,
)

@Serializable data class HelixVideo(
    val id: String, val userId: String, val userLogin: String, val userName: String, val title: String,
    val createdAt: String, val duration: String, val viewCount: Long = 0, val thumbnailUrl: String = "", val type: String = "archive",
)

@Serializable data class HelixClip(
    val id: String, val url: String, val broadcasterId: String, val broadcasterName: String, val creatorName: String,
    val title: String, val viewCount: Long = 0, val createdAt: String, val thumbnailUrl: String, val duration: Double = 0.0,
    val videoId: String? = null, val vodOffset: Int? = null, val gameId: String? = null,
)

@Serializable data class HelixSchedule(val data: HelixScheduleData? = null, val pagination: Pagination? = null)
@Serializable data class HelixScheduleData(val segments: List<HelixSegment>? = null, val broadcasterName: String? = null)
@Serializable data class HelixSegment(
    val id: String, val startTime: String, val endTime: String? = null, val title: String = "",
    val canceledUntil: String? = null, val category: HelixGameRef? = null, val isRecurring: Boolean = false,
)
@Serializable data class HelixGameRef(val id: String, val name: String)

@Serializable data class HelixBadgeSet(val setId: String, val versions: List<HelixBadgeVersion>)
@Serializable data class HelixBadgeVersion(
    val id: String,
    // Snake-case naming gives "image_url1x"; Twitch sends "image_url_1x".
    @SerialName("image_url_1x") val imageUrl1x: String,
    @SerialName("image_url_2x") val imageUrl2x: String,
    @SerialName("image_url_4x") val imageUrl4x: String,
    val title: String? = null,
)

@Serializable data class HelixEmote(
    val id: String, val name: String, val format: List<String> = emptyList(), val scale: List<String> = emptyList(),
    val themeMode: List<String> = emptyList(), val emoteType: String? = null, val emoteSetId: String? = null, val ownerId: String? = null,
    /** "1000", "2000" or "3000" for sub emotes. */
    val tier: String? = null,
)
@Serializable data class HelixEmotePage(val data: List<HelixEmote> = emptyList(), val template: String? = null, val pagination: Pagination? = null)

@Serializable data class HelixChatSettings(
    val broadcasterId: String, val emoteMode: Boolean = false, val followerMode: Boolean = false,
    val followerModeDuration: Int? = null, val slowMode: Boolean = false, val slowModeWaitTime: Int? = null,
    val subscriberMode: Boolean = false, val uniqueChatMode: Boolean = false,
)

@Serializable data class HelixChannelInfo(
    val broadcasterId: String, val broadcasterLogin: String, val broadcasterName: String,
    val gameName: String? = null, val title: String? = null, val tags: List<String>? = null,
    val gameId: String? = null, val broadcasterLanguage: String? = null,
)

// Stream manager
@Serializable data class HelixMarker(val id: String = "", val positionSeconds: Int = 0, val description: String = "")
@Serializable data class HelixCommercial(val length: Int = 0, val message: String = "", val retryAfter: Int = 0)
/** Times are Unix seconds, 0 when none. Parsed by hand: Twitch's docs disagree on the field types. */
data class HelixAdSchedule(
    val nextAdAt: Long = 0, val lastAdAt: Long = 0, val duration: Int = 0,
    val prerollFreeTime: Int = 0, val snoozeCount: Int = 0, val snoozeRefreshAt: Long = 0,
) {
    companion object {
        fun parse(o: JsonObject?): HelixAdSchedule? {
            o ?: return null
            fun num(k: String) = (o[k] as? JsonPrimitive)?.content?.trim().orEmpty()
            fun time(k: String): Long {
                val v = num(k)
                if (v.isEmpty()) return 0
                return v.toLongOrNull() ?: runCatching { OffsetDateTime.parse(v).toEpochSecond() }.getOrDefault(0)
            }
            fun int(k: String) = num(k).toDoubleOrNull()?.toInt() ?: 0
            return HelixAdSchedule(time("next_ad_at"), time("last_ad_at"), int("duration"), int("preroll_free_time"), int("snooze_count"), time("snooze_refresh_at"))
        }
    }
}
@Serializable data class HelixUnbanRequest(
    val id: String, val userId: String, val userLogin: String, val userName: String,
    val text: String = "", val status: String = "", val createdAt: String = "",
)

@Serializable data class HelixSendResult(val messageId: String = "", val isSent: Boolean = false, val dropReason: HelixDropReason? = null)
@Serializable data class HelixDropReason(val code: String = "", val message: String = "")

@Serializable data class HelixChatter(val userId: String, val userLogin: String, val userName: String)

@Serializable data class HelixUserColor(val userId: String, val color: String = "")

@Serializable data class EventSubCreate(
    val type: String,
    val version: String,
    val condition: Map<String, String>,
    val transport: EventSubTransport,
)
// No default on method: the encoder skips defaults, and Twitch rejects a transport without one.
@Serializable data class EventSubTransport(val method: String, val sessionId: String) {
    companion object {
        fun webSocket(sessionId: String) = EventSubTransport("websocket", sessionId)
    }
}
@Serializable data class EventSubCreated(val id: String, val status: String, val type: String)

@Serializable data class HelixBanRequest(val data: HelixBanBody)
@Serializable data class HelixBanBody(val userId: String, val duration: Int? = null, val reason: String? = null)

@Serializable data class HelixSubscription(val broadcasterId: String, val tier: String = "", val isGift: Boolean = false)

typealias RawJson = JsonObject
