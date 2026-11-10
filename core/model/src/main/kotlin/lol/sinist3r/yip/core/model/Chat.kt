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
        /** How many times this exact message arrived in a row (collapse repeats). */
        val repeats: Int = 1,
        val isAction: Boolean = false,
        val replyParentId: String? = null,
        /** First message of the reply thread this belongs to. */
        val threadId: String? = null,
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

/** A Twitch pinned chat message. [endsAt] null means until unpinned or the stream ends. */
data class PinnedMessage(
    val id: String,
    val messageId: String,
    val text: String,
    val senderLogin: String,
    val senderName: String,
    val senderColor: String?,
    val pinnedBy: String?,
    val endsAt: kotlin.time.Instant?,
)

/** Small card for a link in chat. */
data class LinkPreview(val url: String, val title: String, val subtitle: String?, val imageUrl: String?)

/** A one-tap mod action written as "10m Spam", "1h", "ban Bot account". [seconds] null means ban. */
data class ModMacro(val seconds: Int?, val reason: String?) {
    val label: String get() = (seconds?.let(::shortDuration) ?: "Ban") + (reason?.let { " · $it" } ?: "")

    companion object {
        private val FORMAT = Regex("""^\s*(ban|\d+\s*[smhd])\s*(.*)$""", RegexOption.IGNORE_CASE)

        fun parse(line: String): ModMacro? {
            val m = FORMAT.find(line) ?: return null
            val head = m.groupValues[1].lowercase().replace(" ", "")
            val reason = m.groupValues[2].trim().ifBlank { null }
            if (head == "ban") return ModMacro(null, reason)
            val n = head.dropLast(1).toIntOrNull() ?: return null
            val seconds = n * when (head.last()) { 's' -> 1; 'm' -> 60; 'h' -> 3600; else -> 86_400 }
            // Twitch allows 1 second to 2 weeks.
            return if (seconds in 1..1_209_600) ModMacro(seconds, reason) else null
        }

        fun shortDuration(s: Int): String = when {
            s % 86_400 == 0 -> "${s / 86_400}d"
            s % 3600 == 0 -> "${s / 3600}h"
            s % 60 == 0 -> "${s / 60}m"
            else -> "${s}s"
        }
    }
}
