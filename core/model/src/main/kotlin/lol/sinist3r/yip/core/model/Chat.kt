package lol.sinist3r.yip.core.model

import kotlin.time.Instant

/** A piece of a chat message after emote and mention resolution. */
sealed interface ChatToken {
    data class Text(val text: String) : ChatToken
    data class EmoteToken(val emote: Emote, val overlays: List<Emote> = emptyList()) : ChatToken
    data class Mention(val login: String, val display: String) : ChatToken
    data class Link(val url: String) : ChatToken
    data class Cheer(val prefix: String, val bits: Int, val url: String?, val argb: Long?) : ChatToken
}

data class ChatUser(
    val id: String,
    val login: String,
    val displayName: String,
    val colorArgb: Long?,
)

enum class HighlightKind { MENTION, KEYWORD, USER, FIRST_MESSAGE, REDEMPTION, SUB, ANNOUNCEMENT, NONE }

sealed interface ChatItem {
    val id: String
    val timestamp: Instant

    data class Message(
        override val id: String,
        override val timestamp: Instant,
        val channelId: String,
        val user: ChatUser,
        val raw: String,
        val tokens: List<ChatToken>,
        val badges: List<Badge>,
        val paint: Paint? = null,
        val pronouns: String? = null,
        /** Shared chat: name of the channel it was sent in, when not this one. */
        val sharedFrom: String? = null,
        val isAction: Boolean = false,
        val replyParentLogin: String? = null,
        val replyParentText: String? = null,
        val highlight: HighlightKind = HighlightKind.NONE,
        val isFirstMessage: Boolean = false,
        val isDeleted: Boolean = false,
        val isSelf: Boolean = false,
        val isHistory: Boolean = false,
        val bits: Int = 0,
        val rewardTitle: String? = null,
    ) : ChatItem

    /** Sub, gift, raid, announcement, redemption and similar. */
    data class Notice(
        override val id: String,
        override val timestamp: Instant,
        val kind: NoticeKind,
        val text: String,
        val message: Message? = null,
    ) : ChatItem

    /** Client-side status lines (connected, slow mode on, timed out...). */
    /** A message AutoMod is holding for moderator review. [status] is null while pending. */
    data class AutoMod(
        override val id: String,
        override val timestamp: Instant,
        val userLogin: String,
        val userName: String,
        val text: String,
        val reason: String,
        val status: String? = null,
    ) : ChatItem

    data class System(
        override val id: String,
        override val timestamp: Instant,
        val text: String,
    ) : ChatItem
}

enum class NoticeKind { SUB, RESUB, GIFT, MYSTERY_GIFT, RAID, ANNOUNCEMENT, REDEMPTION, CHARITY, BITS_BADGE, WATCH_STREAK, OTHER }

data class RoomState(
    val emoteOnly: Boolean = false,
    val followersOnlyMinutes: Int? = null,
    val slowSeconds: Int = 0,
    val subscriberOnly: Boolean = false,
    val uniqueChat: Boolean = false,
)

sealed interface ChatEvent {
    data class Item(val item: ChatItem) : ChatEvent
    data class Deleted(val messageId: String) : ChatEvent
    data class UserCleared(val userId: String) : ChatEvent
    data object Cleared : ChatEvent
    data class Room(val state: RoomState) : ChatEvent
    data class Connection(val connected: Boolean, val readOnly: Boolean) : ChatEvent
}
