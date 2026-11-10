package dev.yipchat.core.network

import dev.yipchat.core.network.chat.IrcParser
import dev.yipchat.core.network.chat.RawChat
import dev.yipchat.core.network.logs.ChatLogsApi
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ivr_logs.json: a real logs.ivr.fi response (Nov 2026), trimmed to 3 messages. */
class ChatLogsTest {
    @Test fun rawLinesParseAsChat() {
        val lines = ChatLogsApi.parse(Json, javaClass.classLoader!!.getResource("ivr_logs.json")!!.readText())
        assertEquals(3, lines.size)
        val msgs = lines.map { (IrcParser.toRawChat(IrcParser.parseLine(it)!!, isHistory = true) as RawChat.Message).msg }
        assertTrue(msgs.all { it.login == "supibot" && it.isHistory && it.text.isNotBlank() })
        assertTrue(msgs.first().badges.any { it.first == "vip" })
    }
}
