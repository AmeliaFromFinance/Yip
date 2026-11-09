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
    private val twitch = listOf(
        e("Kappa", EmoteProvider.TWITCH).copy(group = "Twitch"),
        e("eepyHi", EmoteProvider.TWITCH, true).copy(group = "eepyAmelia"),
        e("otherSub", EmoteProvider.TWITCH).copy(group = "zoeyStreams"),
        e("anotherSub", EmoteProvider.TWITCH).copy(group = "Alpha"),
        e("HypeCat", EmoteProvider.TWITCH).copy(group = "Hype Train"),
    )

    @Test fun orderAndGrouping() {
        val tabs = emoteTabs(thirdParty, twitch, recent = listOf(e("Kappa", EmoteProvider.TWITCH)), favourites = listOf("SEVENTV:aChan7"))
        assertEquals(listOf("Favourites", "Recent", "Twitch", "3rd Party"), tabs.map { it.name })
        assertEquals(listOf("aChan7"), tabs[0].sections.single().emotes.map { it.code })

        val tw = tabs[2].sections
        // Channel first (incl. tiers not owned yet), then other channels A-Z, then events, then Twitch.
        assertEquals(listOf("This channel", "Tier 1 · locked", "Alpha", "zoeyStreams", "Hype Train", "Twitch"), tw.map { it.title })
        assertEquals(listOf("eepyHi"), tw[0].emotes.map { it.code })
        assertEquals(listOf("SubLocked"), tw[1].emotes.map { it.code })
        assertEquals(true, tw[1].locked)
        assertEquals(listOf("Kappa"), tw.last().emotes.map { it.code })

        val tp = tabs[3].sections
        assertEquals(listOf("7TV", "BTTV", "FFZ"), tp.map { it.title })
        // Channel emotes before globals, no Twitch emotes here.
        assertEquals(listOf("aChan7", "zGlobal7"), tp[0].emotes.map { it.code })
        assertEquals(0, tp.flatMap { it.emotes }.count { it.provider == EmoteProvider.TWITCH })
    }

    @Test fun lockedCanBeHidden() {
        val tw = emoteTabs(thirdParty, twitch, emptyList(), emptyList(), showLocked = false)[2].sections
        assertEquals(false, tw.any { it.locked })
        assertEquals(false, tw.flatMap { it.emotes }.any { it.code == "SubLocked" })
    }

    @Test fun nothingLockedBeforeYourEmotesLoad() {
        val tw = emoteTabs(thirdParty, emptyList(), emptyList(), emptyList())[2].sections
        assertEquals(listOf("SubLocked"), tw.single().emotes.map { it.code })
    }

    @Test fun unlockLabels() {
        assertEquals("Tier 3", lol.sinist3r.yip.core.model.TwitchEmoteGroups.unlockLabel("subscriptions", "3000"))
        assertEquals("Bits", lol.sinist3r.yip.core.model.TwitchEmoteGroups.unlockLabel("bitstier", null))
        assertEquals("Follower", lol.sinist3r.yip.core.model.TwitchEmoteGroups.unlockLabel("follower", null))
    }

    @Test fun emptyTabsStayInPlace() {
        val tabs = emoteTabs(emptyList(), emptyList(), emptyList(), emptyList())
        assertEquals(listOf("Favourites", "Recent", "Twitch", "3rd Party"), tabs.map { it.name })
    }
}

class TwitchEmoteGroupsTest {
    @Test fun grouping() {
        assertEquals("Hype Train", lol.sinist3r.yip.core.model.TwitchEmoteGroups.of("hypetrain", null))
        assertEquals("eepyAmelia", lol.sinist3r.yip.core.model.TwitchEmoteGroups.of("subscriptions", "eepyAmelia"))
        assertEquals("Twitch", lol.sinist3r.yip.core.model.TwitchEmoteGroups.of("globals", null))
        assertEquals("Twitch", lol.sinist3r.yip.core.model.TwitchEmoteGroups.of("follower", null))
    }
}
