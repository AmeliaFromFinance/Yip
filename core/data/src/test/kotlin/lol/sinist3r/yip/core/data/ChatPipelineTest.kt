package lol.sinist3r.yip.core.data

import io.mockk.every
import io.mockk.mockk
import kotlin.time.Clock
import lol.sinist3r.yip.core.data.chat.ChatPipeline
import lol.sinist3r.yip.core.data.cosmetics.ChannelCosmetics
import lol.sinist3r.yip.core.data.cosmetics.CosmeticsRepository
import lol.sinist3r.yip.core.model.AppSettings
import lol.sinist3r.yip.core.model.ChatToken
import lol.sinist3r.yip.core.model.Emote
import lol.sinist3r.yip.core.model.EmoteProvider
import lol.sinist3r.yip.core.model.FilterSettings
import lol.sinist3r.yip.core.model.HighlightKind
import lol.sinist3r.yip.core.network.chat.RawMessage
import lol.sinist3r.yip.core.network.chat.RawPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPipelineTest {
    private val cosmetics = mockk<CosmeticsRepository> {
        every { personalEmotes(any()) } returns emptyMap()
        every { paintFor(any()) } returns null
        every { extraBadges(any()) } returns emptyList()
        every { noteChatter(any()) } returns Unit
    }
    private val pipeline = ChatPipeline(cosmetics)
    private fun emote(code: String, zw: Boolean = false) = Emote(code, code, EmoteProvider.SEVENTV, listOf("https://x/$code"), zeroWidth = zw)
    private val channel = ChannelCosmetics("1", emotes = mapOf("catJAM" to emote("catJAM"), "SoSnowy" to emote("SoSnowy", zw = true)), loaded = true)

    private fun raw(text: String, login: String = "viewer") = RawMessage(
        id = "m", channelId = "1", userId = "u", login = login, displayName = login, color = "#123456",
        badges = emptyList(), parts = listOf(RawPart.Text(text)), text = text, timestamp = Clock.System.now(),
    )

    @Test
    fun `third-party emotes, zero-width overlay, links and mentions`() {
        val m = pipeline.process(raw("hi catJAM SoSnowy @Kael see yip.sinist3r.lol"), channel, AppSettings(), "me")!!
        val emotes = m.tokens.filterIsInstance<ChatToken.EmoteToken>()
        assertEquals(1, emotes.size)
        assertEquals("SoSnowy", emotes.single().overlays.single().code)
        assertTrue(m.tokens.any { it is ChatToken.Mention && it.login == "kael" })
        assertTrue(m.tokens.any { it is ChatToken.Link && it.url == "https://yip.sinist3r.lol" })
    }

    @Test
    fun `filters bots, commands and blocked regex`() {
        val s = AppSettings(filters = FilterSettings(hideCommands = true, blockedTerms = listOf("sp[a4]m"), blockedTermsAreRegex = true))
        assertNull(pipeline.process(raw("hello", login = "nightbot"), channel, s, null))
        assertNull(pipeline.process(raw("!uptime"), channel, s, null))
        assertNull(pipeline.process(raw("buy sp4m now"), channel, s, null))
    }

    @Test
    fun `highlights mentions of the signed-in user`() {
        val m = pipeline.process(raw("hey karasu what's up"), channel, AppSettings(), "karasu")!!
        assertEquals(HighlightKind.MENTION, m.highlight)
        val own = pipeline.process(raw("karasu here", login = "karasu"), channel, AppSettings(), "karasu")!!
        assertEquals(HighlightKind.NONE, own.highlight)
    }

    @Test
    fun `parses twitch colors`() {
        assertEquals(0xFF123456, ChatPipeline.parseColor("#123456"))
        assertNull(ChatPipeline.parseColor("blue"))
    }
}
