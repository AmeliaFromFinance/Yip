package lol.sinist3r.yip.feature.watch

import lol.sinist3r.yip.core.model.Emote
import lol.sinist3r.yip.core.model.EmoteProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class EmoteTabsTest {
    private fun e(code: String, p: EmoteProvider, channel: Boolean = false) = Emote(code, code, p, listOf("u"), isChannel = channel)

    private val thirdParty = listOf(
        e("zGlobal7", EmoteProvider.SEVENTV), e("aChan7", EmoteProvider.SEVENTV, true),
        e("bttvOne", EmoteProvider.BTTV), e("ffzOne", EmoteProvider.FFZ, true),
        e("SubLocked", EmoteProvider.TWITCH, true), // channel's Twitch emote from cosmetics, not in the user's own list
    )
    private val twitch = listOf(e("Kappa", EmoteProvider.TWITCH), e("eepyHi", EmoteProvider.TWITCH, true), e("otherSub", EmoteProvider.TWITCH))

    @Test fun orderAndGrouping() {
        val tabs = emoteTabs(thirdParty, twitch, recent = listOf(e("Kappa", EmoteProvider.TWITCH)), favourites = listOf("SEVENTV:aChan7"))
        assertEquals(listOf("Favourites", "Recent", "Twitch", "3rd Party"), tabs.map { it.name })
        assertEquals(listOf("aChan7"), tabs[0].sections.single().emotes.map { it.code })

        val tw = tabs[2].sections
        assertEquals(listOf("This channel", "Your emotes"), tw.map { it.title })
        assertEquals(listOf("eepyHi"), tw[0].emotes.map { it.code })
        assertEquals(listOf("Kappa", "otherSub"), tw[1].emotes.map { it.code })

        val tp = tabs[3].sections
        assertEquals(listOf("7TV", "BTTV", "FFZ"), tp.map { it.title })
        // Channel emotes before globals, no Twitch emotes here.
        assertEquals(listOf("aChan7", "zGlobal7"), tp[0].emotes.map { it.code })
        assertEquals(0, tp.flatMap { it.emotes }.count { it.provider == EmoteProvider.TWITCH })
    }

    @Test fun emptyTabsStayInPlace() {
        val tabs = emoteTabs(emptyList(), emptyList(), emptyList(), emptyList())
        assertEquals(listOf("Favourites", "Recent", "Twitch", "3rd Party"), tabs.map { it.name })
    }
}
