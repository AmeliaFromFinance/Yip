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

class SharedChatIrcTest {
    @Test fun sourceRoomFromOtherChannel() {
        val line = "@badges=;color=#1E90FF;display-name=Visitor;id=abc;room-id=111;source-room-id=222;source-id=xyz;tmi-sent-ts=1700000000000;user-id=9 :visitor!visitor@visitor.tmi.twitch.tv PRIVMSG #host :hello"
        val msg = (IrcParser.toRawChat(IrcParser.parseLine(line)!!) as RawChat.Message).msg
        org.junit.Assert.assertEquals("222", msg.sourceChannelId)
    }

    @Test fun ownChannelIsNotShared() {
        val line = "@badges=;display-name=Host;id=abd;room-id=111;source-room-id=111;tmi-sent-ts=1700000000000;user-id=1 :host!host@host.tmi.twitch.tv PRIVMSG #host :hi"
        val msg = (IrcParser.toRawChat(IrcParser.parseLine(line)!!) as RawChat.Message).msg
        org.junit.Assert.assertNull(msg.sourceChannelId)
    }
}

class ReplyThreadIrcTest {
    @Test fun threadAndParentIds() {
        val line = "@badges=;display-name=B;id=m3;reply-parent-msg-id=m2;reply-parent-display-name=A;reply-parent-msg-body=hi;reply-thread-parent-msg-id=m1;room-id=1;tmi-sent-ts=1700000000000;user-id=2 :b!b@b.tmi.twitch.tv PRIVMSG #c :@A yo"
        val msg = (IrcParser.toRawChat(IrcParser.parseLine(line)!!) as RawChat.Message).msg
        org.junit.Assert.assertEquals("m2", msg.replyParentId)
        org.junit.Assert.assertEquals("m1", msg.replyThreadId)
    }
}
