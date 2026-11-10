package lol.sinist3r.yip.core.network.chat

import kotlin.time.Clock
import kotlin.time.Instant

/** Parses Twitch IRCv3 lines (also used for the recent-messages history service). */
object IrcParser {

    data class Line(val tags: Map<String, String>, val prefix: String?, val command: String, val params: List<String>) {
        val trailing: String? get() = params.lastOrNull()
        val nick: String? get() = prefix?.substringBefore('!')
    }

    fun parseLine(raw: String): Line? {
        var rest = raw.trimEnd('\r', '\n')
        if (rest.isEmpty()) return null
        var tags = emptyMap<String, String>()
        if (rest.startsWith('@')) {
            val end = rest.indexOf(' ')
            if (end < 0) return null
            tags = rest.substring(1, end).split(';').associate {
                val i = it.indexOf('=')
                if (i < 0) it to "" else it.substring(0, i) to unescape(it.substring(i + 1))
            }
            rest = rest.substring(end + 1)
        }
        var prefix: String? = null
        if (rest.startsWith(':')) {
            val end = rest.indexOf(' ')
            if (end < 0) return null
            prefix = rest.substring(1, end)
            rest = rest.substring(end + 1)
        }
        val trailingIdx = rest.indexOf(" :")
        val head = if (trailingIdx >= 0) rest.substring(0, trailingIdx) else rest
        val parts = head.split(' ').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val params = parts.drop(1).toMutableList()
        if (trailingIdx >= 0) params += rest.substring(trailingIdx + 2)
        return Line(tags, prefix, parts[0], params)
    }

    private fun unescape(v: String): String {
        if ('\\' !in v) return v
        val sb = StringBuilder(v.length)
        var i = 0
        while (i < v.length) {
            val c = v[i]
            if (c == '\\' && i + 1 < v.length) {
                when (v[i + 1]) {
                    's' -> sb.append(' ')
                    ':' -> sb.append(';')
                    '\\' -> sb.append('\\')
                    'r' -> sb.append('\r')
                    'n' -> sb.append('\n')
                    else -> sb.append(v[i + 1])
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    fun toRawChat(line: Line, isHistory: Boolean = false): RawChat? = when (line.command) {
        "PRIVMSG" -> RawChat.Message(message(line, line.trailing.orEmpty(), isHistory))
        "USERNOTICE" -> userNotice(line, isHistory)
        "CLEARMSG" -> line.tags["target-msg-id"]?.let(RawChat::Deleted)
        "CLEARCHAT" -> {
            val target = line.tags["target-user-id"]
            if (target != null) RawChat.ClearUser(target, line.tags["ban-duration"]?.toIntOrNull()) else RawChat.Clear
        }
        "ROOMSTATE" -> RawChat.Room(
            emoteOnly = line.tags["emote-only"]?.let { it == "1" },
            followersOnlyMinutes = line.tags["followers-only"]?.toIntOrNull()?.takeIf { it >= 0 },
            followersOnly = line.tags["followers-only"]?.let { it != "-1" },
            slowSeconds = line.tags["slow"]?.toIntOrNull(),
            subsOnly = line.tags["subs-only"]?.let { it == "1" },
            unique = line.tags["r9k"]?.let { it == "1" },
            channelId = line.tags["room-id"],
        )
        "NOTICE" -> line.trailing?.let(RawChat::ServerNotice)
        else -> null
    }

    private fun timestamp(tags: Map<String, String>): Instant =
        tags["tmi-sent-ts"]?.toLongOrNull()?.let(Instant::fromEpochMilliseconds) ?: Clock.System.now()

    fun message(line: Line, textIn: String, isHistory: Boolean): RawMessage {
        val t = line.tags
        var text = textIn
        var action = false
        if (text.startsWith("\u0001ACTION ") && text.endsWith('\u0001')) {
            text = text.removePrefix("\u0001ACTION ").removeSuffix("\u0001")
            action = true
        }
        val login = line.nick ?: t["login"].orEmpty()
        return RawMessage(
            id = t["id"] ?: "${login}-${t["tmi-sent-ts"]}",
            channelId = t["room-id"].orEmpty(),
            userId = t["user-id"].orEmpty(),
            login = login,
            displayName = t["display-name"]?.takeIf { it.isNotBlank() } ?: login,
            color = t["color"]?.takeIf { it.isNotBlank() },
            badges = t["badges"].orEmpty().split(',').filter { '/' in it }.map { it.substringBefore('/') to it.substringAfter('/') },
            parts = splitEmotes(text, t["emotes"].orEmpty()),
            text = text,
            timestamp = timestamp(t),
            isAction = action,
            isFirstMessage = t["first-msg"] == "1",
            sourceChannelId = t["source-room-id"]?.takeIf { it.isNotBlank() && it != t["room-id"] },
            replyParentId = t["reply-parent-msg-id"],
            replyThreadId = t["reply-thread-parent-msg-id"],
            replyParentLogin = t["reply-parent-display-name"] ?: t["reply-parent-user-login"],
            replyParentText = t["reply-parent-msg-body"],
            bits = t["bits"]?.toIntOrNull() ?: 0,
            rewardId = t["custom-reward-id"],
            isHighlightedRedemption = t["msg-id"] == "highlighted-message",
            isHistory = isHistory || t["historical"] == "1",
        )
    }

    /** IRC emote ranges are code point offsets: "25:0-4,12-16/1902:6-10". */
    fun splitEmotes(text: String, emotesTag: String): List<RawPart> {
        if (emotesTag.isBlank()) return listOf(RawPart.Text(text))
        val cps = text.codePoints().toArray()
        val ranges = emotesTag.split('/').flatMap { group ->
            val id = group.substringBefore(':')
            group.substringAfter(':').split(',').mapNotNull { r ->
                val a = r.substringBefore('-').toIntOrNull() ?: return@mapNotNull null
                val b = r.substringAfter('-').toIntOrNull() ?: return@mapNotNull null
                Triple(a, b, id)
            }
        }.filter { it.first <= it.second && it.second < cps.size }.sortedBy { it.first }
        val out = mutableListOf<RawPart>()
        var cursor = 0
        fun sub(from: Int, to: Int) = String(cps, from, to - from)
        for ((a, b, id) in ranges) {
            if (a < cursor) continue
            if (a > cursor) out += RawPart.Text(sub(cursor, a))
            out += RawPart.TwitchEmote(id, sub(a, b + 1))
            cursor = b + 1
        }
        if (cursor < cps.size) out += RawPart.Text(sub(cursor, cps.size))
        return out
    }

    private fun userNotice(line: Line, isHistory: Boolean): RawChat {
        val t = line.tags
        val kind = when (t["msg-id"]) {
            "sub" -> RawNoticeKind.SUB
            "resub" -> RawNoticeKind.RESUB
            "subgift", "anonsubgift", "giftpaidupgrade", "anongiftpaidupgrade", "primepaidupgrade" -> RawNoticeKind.GIFT
            "submysterygift", "anonsubmysterygift" -> RawNoticeKind.MYSTERY_GIFT
            "raid" -> RawNoticeKind.RAID
            "announcement" -> RawNoticeKind.ANNOUNCEMENT
            "bitsbadgetier" -> RawNoticeKind.BITS_BADGE
            "charitydonation" -> RawNoticeKind.CHARITY
            "viewermilestone" -> RawNoticeKind.WATCH_STREAK
            else -> RawNoticeKind.OTHER
        }
        val msg = line.trailing?.takeIf { it.isNotBlank() }?.let { message(line, it, isHistory) }
        return RawChat.Notice(
            id = t["id"] ?: "notice-${t["tmi-sent-ts"]}",
            kind = kind,
            systemText = t["system-msg"].orEmpty(),
            msg = msg,
            timestamp = timestamp(t),
        )
    }
}
