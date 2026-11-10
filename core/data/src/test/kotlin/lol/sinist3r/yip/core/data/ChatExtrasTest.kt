package lol.sinist3r.yip.core.data

import kotlin.time.Clock
import lol.sinist3r.yip.core.data.chat.ChatCommands
import lol.sinist3r.yip.core.data.chat.isRepeat
import lol.sinist3r.yip.core.model.ChatItem
import lol.sinist3r.yip.core.model.ChatUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatExtrasTest {
    private fun m(id: String, user: String, text: String, reply: String? = null) =
        ChatItem.Message(id, Clock.System.now(), "1", ChatUser(id, user, user, null), text, emptyList(), emptyList(), replyParentLogin = reply)

    @Test fun repeatsIgnoreCaseSpacingAndSender() {
        assertTrue(isRepeat(m("1", "a", "KEKW  LUL"), m("2", "b", "kekw lul")))
        assertFalse(isRepeat(m("1", "a", "KEKW"), m("2", "b", "KEKW!")))
        assertFalse(isRepeat(m("1", "a", "yes", reply = "x"), m("2", "b", "yes")))
    }

    @Test fun commandsByRole() {
        val viewer = ChatCommands.available(isMod = false, isBroadcaster = false).map { it.name }
        val mod = ChatCommands.available(isMod = true, isBroadcaster = false).map { it.name }
        val owner = ChatCommands.available(isMod = true, isBroadcaster = true).map { it.name }
        assertTrue("/me" in viewer && "/timeout" !in viewer)
        assertTrue("/timeout" in mod && "/raid" !in mod)
        assertTrue("/raid" in owner && "/vip" in owner)
        assertEquals(ChatCommands.all.size, owner.size)
    }
}
