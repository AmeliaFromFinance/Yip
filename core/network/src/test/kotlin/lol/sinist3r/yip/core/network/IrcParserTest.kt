package lol.sinist3r.yip.core.network

import lol.sinist3r.yip.core.network.chat.IrcParser
import lol.sinist3r.yip.core.network.chat.RawChat
import lol.sinist3r.yip.core.network.chat.RawNoticeKind
import lol.sinist3r.yip.core.network.chat.RawPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IrcParserTest {
    @Test
    fun `parses privmsg with tags, escaping and emotes`() {
        val line = "@badges=moderator/1,subscriber/12;color=#FF4500;display-name=Kael;emotes=25:6-10;first-msg=0;id=abc;room-id=42;tmi-sent-ts=1700000000000;user-id=7;reply-parent-msg-body=hi\\sthere " +
            ":kael!kael@kael.tmi.twitch.tv PRIVMSG #chan :hello Kappa world"
        val msg = (IrcParser.toRawChat(IrcParser.parseLine(line)!!) as RawChat.Message).msg
        assertEquals("Kael", msg.displayName)
        assertEquals("#FF4500", msg.color)
        assertEquals(listOf("moderator" to "1", "subscriber" to "12"), msg.badges)
        assertEquals("hi there", msg.replyParentText)
        assertEquals(listOf(RawPart.Text("hello "), RawPart.TwitchEmote("25", "Kappa"), RawPart.Text(" world")), msg.parts)
    }

    @Test
    fun `emote ranges count code points, not UTF-16 units`() {
        // The emoji before Kappa is two UTF-16 chars but one code point.
        val parts = IrcParser.splitEmotes("😀 Kappa", "25:2-6")
        assertEquals(RawPart.TwitchEmote("25", "Kappa"), parts.last())
    }

    @Test
    fun `parses action, clearchat and usernotice`() {
        val action = IrcParser.toRawChat(IrcParser.parseLine("@id=1 :a!a@a PRIVMSG #c :\u0001ACTION waves\u0001")!!) as RawChat.Message
        assertTrue(action.msg.isAction)
        assertEquals("waves", action.msg.text)

        val timeout = IrcParser.toRawChat(IrcParser.parseLine("@ban-duration=600;target-user-id=99 :tmi.twitch.tv CLEARCHAT #c :bob")!!)
        assertEquals(RawChat.ClearUser("99", 600), timeout)

        val raid = IrcParser.toRawChat(IrcParser.parseLine("@msg-id=raid;system-msg=5\\sraiders;id=r1 :tmi.twitch.tv USERNOTICE #c")!!) as RawChat.Notice
        assertEquals(RawNoticeKind.RAID, raid.kind)
        assertEquals("5 raiders", raid.systemText)
    }
}
