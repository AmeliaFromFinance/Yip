package dev.yipchat.core.data.chat

import dev.yipchat.core.data.cosmetics.ChannelCosmetics
import dev.yipchat.core.data.cosmetics.CosmeticsRepository
import dev.yipchat.core.data.cosmetics.twitchEmote
import dev.yipchat.core.model.AppSettings
import dev.yipchat.core.model.Badge
import dev.yipchat.core.model.ChatItem
import dev.yipchat.core.model.ChatToken
import dev.yipchat.core.model.ChatUser
import dev.yipchat.core.model.Emote
import dev.yipchat.core.model.HighlightKind
import dev.yipchat.core.model.PaintMode
import dev.yipchat.core.network.chat.RawMessage
import dev.yipchat.core.network.chat.RawPart
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns transport messages into render-ready [ChatItem.Message]s. Runs off the main thread;
 * nothing here touches Compose.
 */
class ChatPipeline(private val cosmetics: CosmeticsRepository, private val pronouns: PronounRepository? = null, private val sharedNames: SharedChatNames? = null) {


    fun process(
        raw: RawMessage,
        channel: ChannelCosmetics,
        settings: AppSettings,
        selfLogin: String?,
    ): ChatItem.Message? {
        if (isFiltered(raw, settings)) return null
        cosmetics.noteChatter(raw.userId)
        pronouns?.note(raw.userId, raw.login)
        val personal = cosmetics.personalEmotes(raw.userId)
        val tokens = tokenize(raw.parts, channel.emotes, personal, settings)
        if (settings.filters.hideEmoteOnly && tokens.isNotEmpty() && tokens.all { it is ChatToken.EmoteToken || (it is ChatToken.Text && it.text.isBlank()) }) return null

        val paint = if (settings.thirdParty.seventvPaints != PaintMode.OFF) cosmetics.paintFor(raw.userId) else null
        return ChatItem.Message(
            id = raw.id,
            timestamp = raw.timestamp,
            channelId = raw.channelId.ifEmpty { channel.channelId },
            user = ChatUser(raw.userId, raw.login, raw.displayName, parseColor(raw.color)),
            raw = raw.text,
            tokens = tokens,
            badges = if (settings.chat.showBadges) badges(raw, channel) else emptyList(),
            paint = paint,
            pronouns = if (settings.chat.showPronouns) pronouns?.cached(raw.login) else null,
            isAction = raw.isAction,
            replyParentId = raw.replyParentId,
            threadId = raw.replyThreadId ?: raw.replyParentId,
            replyParentLogin = raw.replyParentLogin,
            replyParentText = raw.replyParentText,
            highlight = highlight(raw, selfLogin, settings),
            isFirstMessage = raw.isFirstMessage,
            isSelf = selfLogin != null && raw.login.equals(selfLogin, true),
            isHistory = raw.isHistory,
            bits = raw.bits,
            sharedFrom = raw.sourceChannelId?.let { id -> raw.sourceChannelName ?: sharedNames?.nameFor(id) ?: "another channel" },
            rewardId = raw.rewardId,
        )
    }

    private fun isFiltered(raw: RawMessage, s: AppSettings): Boolean =
        hides(raw.login, raw.text, raw.isFirstMessage, s)

    /** Same rules for a message already on screen, used when filters change mid-chat. */
    fun hides(m: ChatItem.Message, s: AppSettings): Boolean = hides(m.user.login, m.raw, m.isFirstMessage, s)

    private fun hides(loginRaw: String, text: String, firstMessage: Boolean, s: AppSettings): Boolean {
        val f = s.filters
        val login = loginRaw.lowercase()
        if (f.hideBots && login in f.botNames) return true
        if (login in f.hiddenUsers) return true
        if (f.hideCommands && f.commandPrefixes.any { text.startsWith(it) }) return true
        if (f.hideFirstTimeChatters && firstMessage) return true
        return f.blockedTerms.isNotEmpty() && blocked(f.blockedTerms, f.blockedTermsAreRegex).matches(text)
    }

    // Whole words or /regex/, like highlight keywords. The old "all regex" switch still applies to saved lists.
    @Volatile private var blockedCache: Triple<List<String>, Boolean, KeywordMatcher>? = null
    private fun blocked(list: List<String>, allRegex: Boolean): KeywordMatcher =
        blockedCache?.takeIf { it.first == list && it.second == allRegex }?.third
            ?: KeywordMatcher(if (allRegex) list.map { if (KeywordMatcher.isPattern(it)) it else "/$it/" } else list)
                .also { blockedCache = Triple(list, allRegex, it) }

    fun tokenize(parts: List<RawPart>, emotes: Map<String, Emote>, personal: Map<String, Emote>, s: AppSettings): List<ChatToken> {
        val out = ArrayList<ChatToken>(parts.size * 2)
        val text = StringBuilder()
        fun flush() {
            if (text.isNotEmpty()) {
                out += ChatToken.Text(text.toString())
                text.setLength(0)
            }
        }
        fun addEmote(e: Emote) {
            if (e.zeroWidth && s.thirdParty.zeroWidthEmotes) {
                // Overlay on the previous emote if it's directly before us (ignoring one space).
                val trimmed = text.isBlank()
                val prev = out.lastOrNull()
                if (trimmed && prev is ChatToken.EmoteToken) {
                    text.setLength(0)
                    out[out.lastIndex] = prev.copy(overlays = prev.overlays + e)
                    return
                }
            }
            flush()
            out += ChatToken.EmoteToken(e)
        }
        for (part in parts) {
            when (part) {
                is RawPart.TwitchEmote -> addEmote(twitchEmote(part.id, part.code))
                is RawPart.Cheer -> { flush(); out += ChatToken.Cheer(part.prefix, part.bits, null, null) }
                is RawPart.Mention -> { flush(); out += ChatToken.Mention(part.login, part.display) }
                is RawPart.Text -> {
                    var i = 0
                    val str = part.text
                    while (i < str.length) {
                        if (str[i] == ' ') {
                            text.append(' ')
                            i++
                            continue
                        }
                        var j = str.indexOf(' ', i)
                        if (j < 0) j = str.length
                        val word = str.substring(i, j)
                        val emote = personal[word] ?: emotes[word]
                        when {
                            emote != null -> addEmote(emote)
                            s.chat.clickableLinks && isLink(word) -> { flush(); out += ChatToken.Link(normalizeLink(word)) }
                            word.length > 1 && word[0] == '@' -> {
                                flush()
                                val name = word.substring(1).trimEnd(',', '.', '!', '?', ':')
                                out += ChatToken.Mention(name.lowercase(), name)
                                if (name.length + 1 < word.length) text.append(word.substring(name.length + 1))
                            }
                            else -> text.append(word)
                        }
                        i = j
                    }
                }
            }
        }
        flush()
        return out
    }

    private fun badges(raw: RawMessage, ch: ChannelCosmetics): List<Badge> {
        val out = ArrayList<Badge>(raw.badges.size + 2)
        for ((set, version) in raw.badges) {
            val badge = when {
                set == "moderator" && ch.modBadge != null -> ch.modBadge
                set == "vip" && ch.vipBadge != null -> ch.vipBadge
                else -> ch.twitchBadges["$set/$version"] ?: ch.twitchBadges["$set/1"]
            }
            badge?.let(out::add)
        }
        out += cosmetics.extraBadges(raw.userId)
        return out
    }

    private fun highlight(raw: RawMessage, self: String?, s: AppSettings): HighlightKind {
        val text = raw.text
        if (s.chat.highlightMentions && self != null && !raw.login.equals(self, true)) {
            if (raw.replyParentLogin.equals(self, true) || mentionRegex(self).containsMatchIn(text)) return HighlightKind.MENTION
        }
        if (raw.login.lowercase() in s.filters.highlightUsers) return HighlightKind.USER
        if (s.chat.highlightBroadcaster && raw.badges.any { it.first == "broadcaster" }) return HighlightKind.BROADCASTER
        if (keywords(s.filters.highlightKeywords).matches(text)) return HighlightKind.KEYWORD
        if (raw.isHighlightedRedemption || raw.rewardId != null) return HighlightKind.REDEMPTION
        if (raw.isFirstMessage && s.chat.showFirstMessageTag) return HighlightKind.FIRST_MESSAGE
        return HighlightKind.NONE
    }

    // Rebuilt only when the keyword list changes.
    @Volatile private var keywordCache: Pair<List<String>, KeywordMatcher>? = null
    private fun keywords(list: List<String>): KeywordMatcher =
        keywordCache?.takeIf { it.first === list || it.first == list }?.second ?: KeywordMatcher(list).also { keywordCache = list to it }

    private val mentionCache = ConcurrentHashMap<String, Regex>()
    private fun mentionRegex(login: String) = mentionCache.getOrPut(login) { Regex("""(?i)(^|[^\w])@?${Regex.escape(login)}\b""") }

    companion object {
        private val linkRegex = Regex("""^(https?://)?([\w-]+\.)+[a-z]{2,}(/\S*)?$""", RegexOption.IGNORE_CASE)

        fun isLink(word: String) = word.length > 4 && '.' in word && linkRegex.matches(word.trimEnd(',', ')', '.'))
        fun normalizeLink(word: String): String = word.trimEnd(',', ')', '.').let { if (it.startsWith("http")) it else "https://$it" }

        fun parseColor(hex: String?): Long? {
            if (hex == null || hex.length != 7 || hex[0] != '#') return null
            return hex.substring(1).toLongOrNull(16)?.let { 0xFF000000L or it }
        }
    }
}
