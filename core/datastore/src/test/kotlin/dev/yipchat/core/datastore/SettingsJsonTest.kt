package dev.yipchat.core.datastore

import dev.yipchat.core.model.AccentColor
import dev.yipchat.core.model.AppSettings
import dev.yipchat.core.model.AppearanceSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SettingsJsonTest {
    @Test
    fun `round trips`() {
        val s = AppSettings(appearance = AppearanceSettings(accent = AccentColor.MINT, gridColumns = 3))
        assertEquals(s, SettingsJson.import(SettingsJson.export(s)))
    }

    @Test
    fun `tolerates unknown and missing keys from other versions`() {
        val s = SettingsJson.import("""{"schema":1,"futureThing":true,"appearance":{"accent":"ROSE"}}""")
        assertEquals(AccentColor.ROSE, s.appearance.accent)
        assertEquals(AppSettings().player, s.player)
    }

    @Test
    fun `ad handling is off by default`() {
        assertFalse(AppSettings().adBlock.enabled)
        assertFalse(AppSettings().adBlock.acknowledgedRisks)
        assertFalse(AppSettings().experimental.autoClaimPoints)
    }
}
