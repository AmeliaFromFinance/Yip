package lol.sinist3r.yip.core.network.chat

import kotlin.time.Instant

/** Transport-neutral chat events produced by both IRC and EventSub. */
sealed interface RawPart {
    data class Text(val text: String) : RawPart
    data class TwitchEmote(val id: String, val code: String) : RawPart
    data class Cheer(val prefix: String, val bits: Int, val tier: Int) : RawPart
    data class Mention(val login: String, val display: String) : RawPart
}

data class RawMessage(
    val id: String,
    val channelId: String,
    val userId: String,
    val login: String,
    val displayName: String,
    val color: String?,
    val badges: List<Pair<String, String>>,
    val parts: List<RawPart>,
    val text: String,
    val timestamp: Instant,
    val isAction: Boolean = false,
    val isFirstMessage: Boolean = false,
    val replyParentId: String? = null,
    val replyParentLogin: String? = null,
    val replyParentText: String? = null,
    val bits: Int = 0,
    val rewardId: String? = null,
    val isHighlightedRedemption: Boolean = false,
    val isHistory: Boolean = false,
    /** Shared chat: the channel this message was sent in, when it isn't this one. */
    val sourceChannelId: String? = null,
    val sourceChannelName: String? = null,
)

enum class RawNoticeKind { SUB, RESUB, GIFT, MYSTERY_GIFT, RAID, ANNOUNCEMENT, BITS_BADGE, CHARITY, WATCH_STREAK, REDEMPTION, OTHER }

sealed interface RawChat {
    data class Message(val msg: RawMessage) : RawChat
    /** Twitch refused the chat subscription; [reason] is Twitch's own wording when it gave one. */
    data class AutoModHeld(val messageId: String, val login: String, val displayName: String, val text: String, val reason: String) : RawChat
    data class AutoModResolved(val messageId: String, val status: String) : RawChat
    data class JoinFailed(val reason: String, val notAllowed: Boolean) : RawChat
    data class Notice(val id: String, val kind: RawNoticeKind, val systemText: String, val msg: RawMessage?, val timestamp: Instant) : RawChat
    data class Deleted(val messageId: String) : RawChat
    data class ClearUser(val userId: String, val durationSeconds: Int?) : RawChat
    data object Clear : RawChat
    data class Room(
        val emoteOnly: Boolean? = null,
        val followersOnlyMinutes: Int? = null,
        val followersOnly: Boolean? = null,
        val slowSeconds: Int? = null,
        val subsOnly: Boolean? = null,
        val unique: Boolean? = null,
        val channelId: String? = null,
    ) : RawChat
    data class Status(val connected: Boolean, val readOnly: Boolean, val message: String? = null) : RawChat
    data class ServerNotice(val text: String) : RawChat
    /** The channel being watched raided out to another channel. */
    data class Whisper(val id: String, val fromId: String, val fromLogin: String, val fromName: String, val text: String) : RawChat
    data class RaidOut(val toLogin: String, val toName: String, val viewers: Int) : RawChat
}
