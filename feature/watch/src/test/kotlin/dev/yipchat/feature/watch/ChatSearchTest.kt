package dev.yipchat.feature.watch

import dev.yipchat.core.data.chat.MentionInbox
import dev.yipchat.core.model.ChatItem
import dev.yipchat.core.model.ChatToken
import dev.yipchat.core.model.ChatUser
import dev.yipchat.core.model.HighlightKind
import kotlin.time.Clock
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatSearchTest {
    private val now = Clock.System.now()
    private fun msg(id: String, login: String, text: String, highlight: HighlightKind = HighlightKind.NONE) =
        ChatItem.Message(id, now, "1", ChatUser(id, login, login.replaceFirstChar(Char::uppercase), null), text, listOf(ChatToken.Text(text)), emptyList(), highlight = highlight)

    private val chat = listOf(
        msg("1", "amelia", "hello chat"),
        msg("2", "nightbot", "Follow the socials"),
        msg("3", "amelia", "Hello again, socials are up"),
        msg("4", "zoey", "hi"),
    )

    @Test fun wordsMatchAnyCaseNewestFirst() = assertEquals(listOf("3", "1"), searchChat(chat, "HELLO").map { it.id })
    @Test fun fromLimitsToChatter() = assertEquals(listOf("3"), searchChat(chat, "from:ame socials").map { it.id })
    @Test fun fromAlone() = assertEquals(listOf("4"), searchChat(chat, "from:@zoey").map { it.id })
    @Test fun blankFindsNothing() = assertEquals(0, searchChat(chat, "  ").size)

    @Test fun inboxKeepsOnlyMentions() {
        val inbox = MentionInbox()
        inbox.onMessage("eepyamelia", msg("a", "zoey", "@me hi", HighlightKind.MENTION))
        inbox.onMessage("eepyamelia", msg("b", "zoey", "no ping"))
        inbox.onMessage("eepyamelia", msg("a", "zoey", "@me hi", HighlightKind.MENTION)) // duplicate id
        assertEquals(listOf("a"), inbox.items.value.map { it.message.id })
        inbox.markRead()
        assertEquals(0, inbox.unread.value)
    }
}
