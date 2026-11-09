package lol.sinist3r.yip.core.data.twitch

import lol.sinist3r.yip.core.model.Channel
import org.junit.Assert.assertEquals
import org.junit.Test

class RankChannelsTest {
    private fun ch(login: String, id: String = login) = Channel(id, login, login, null, null, false, null)

    // Roughly what Twitch returned for "eepyAmelia": popular "eepy" channels first.
    private val twitchOrder = listOf(ch("eepy_melatonin"), ch("eepyboi"), ch("ameliawatson"), ch("eepyamelia_fan"), ch("sleepy_cat"))

    @Test fun exactNamePinnedFirstEvenWhenTwitchOmitsIt() {
        val ranked = rankChannels("eepyAmelia", twitchOrder, exact = ch("eepyamelia", id = "1"))
        assertEquals("eepyamelia", ranked.first().login)
    }

    @Test fun thenStartsWithThenContainsThenTheRest() {
        val ranked = rankChannels("eepyAmelia", twitchOrder + ch("eepyamelia"), exact = null)
        assertEquals(listOf("eepyamelia", "eepyamelia_fan", "eepy_melatonin", "eepyboi", "ameliawatson", "sleepy_cat"), ranked.map { it.login })
    }

    @Test fun noDuplicateWhenTwitchAlsoReturnsTheExactMatch() {
        val exact = ch("eepyamelia", id = "1")
        val ranked = rankChannels("eepyamelia", listOf(ch("other"), exact), exact)
        assertEquals(2, ranked.size)
    }
}
