package lol.sinist3r.yip.core.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import lol.sinist3r.yip.core.network.cosmetics.PronounDef
import lol.sinist3r.yip.core.network.cosmetics.PronounsApi
import lol.sinist3r.yip.core.network.twitch.bool
import lol.sinist3r.yip.core.network.twitch.str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Uses the live pronoun list from api.pronouns.alejo.io/v1/pronouns. */
class PronounsTest {
    private val defs: Map<String, PronounDef> = Json.parseToJsonElement(
        checkNotNull(javaClass.classLoader?.getResource("pronouns.json")).readText(),
    ).jsonObject.mapValues { (_, v) -> (v as JsonObject).let { PronounDef(it.str("subject")!!, it.str("object").orEmpty(), it.bool("singular") == true) } }

    @Test fun labels() {
        assertEquals("She/They", PronounsApi.label("sheher", "theythem", defs))
        assertEquals("He/Him", PronounsApi.label("hehim", null, defs))
        assertEquals("Any", PronounsApi.label("any", null, defs))
        assertNull(PronounsApi.label("not_a_real_id", null, defs))
    }
}
