package dev.yipchat.core.datastore

import dev.yipchat.core.model.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTransferTest {
    // A heavy but realistic setup: lots of filters, pins and favourite emotes.
    private val heavy = AppSettings().let { s ->
        s.copy(
            filters = s.filters.copy(
                blockedTerms = (1..60).map { "blockedword$it" } + "/bit\\.ly/",
                hiddenUsers = (1..40).map { "someuser_$it" },
                highlightKeywords = listOf("eepy", "amelia"),
            ),
            notifications = s.notifications.copy(favourites = (1..30).map { "channel_$it" }),
            chat = s.chat.copy(favouriteEmotes = (1..40).map { "SEVENTV:01F${it}ABCDEFGHJKMNPQRSTVWX" }),
        )
    }

    @Test fun roundTrip() {
        assertEquals(heavy, SettingsTransfer.decode(SettingsTransfer.encode(heavy)))
    }

    @Test fun heavySettingsFitInQr() {
        val payload = SettingsTransfer.encode(heavy)
        assertTrue("payload ${payload.length} chars", payload.length <= SettingsTransfer.QR_MAX_PAYLOAD)
    }

    @Test fun defaultsAreTiny() {
        assertTrue(SettingsTransfer.encode(AppSettings()).length < 20)
    }

    @Test(expected = Exception::class) fun rejectsJunk() {
        SettingsTransfer.decode("bm90IGEgeWlwIGxpbms")
    }
}
