package com.nuvio.app.features.player.desktop.refreshrate.runtime

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.player.PlayerSettingsStorage
import com.nuvio.app.features.settings.MATCH_DISPLAY_REFRESH_RATE_KEY
import com.nuvio.app.features.settings.REFRESH_RATE_STORE
import com.nuvio.app.features.settings.RefreshRateMatchPreference
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** SPEC P6-3, P6-4: the per-PC store. Every test uses its own temp file, never DesktopStorage's folders. */
class RefreshRateMatchSettingTest {
    private val dir: Path = Files.createTempDirectory("nuvio-rr-setting")
    private val file: Path = dir.resolve("$REFRESH_RATE_STORE.properties")

    @AfterTest
    fun tearDown() {
        file.deleteIfExists()
        dir.deleteIfExists()
    }

    private fun preference() = RefreshRateMatchPreference(DesktopStorage.Store(file))

    @Test
    fun `default is OFF when no file exists`() {
        assertFalse(Files.exists(file))
        val preference = preference()
        assertFalse(preference.stored())
        assertFalse(preference.enabled.value)
        assertFalse(Files.exists(file), "reading must not create the file")
    }

    @Test
    fun `default is OFF when the file has no key`() {
        file.writeText("other_key=true\n")
        assertFalse(preference().stored())
        assertFalse(preference().enabled.value)
    }

    @Test
    fun `round trip through the file`() {
        preference().setEnabled(true)
        assertTrue(file.readText().contains("$MATCH_DISPLAY_REFRESH_RATE_KEY=true"))
        assertTrue(preference().stored(), "a new store on the same file (app restart) reads it back")
        assertTrue(preference().enabled.value)
        preference().setEnabled(false)
        assertFalse(preference().stored())
    }

    @Test
    fun `setEnabled updates the StateFlow and stored`() {
        val preference = preference()
        preference.setEnabled(true)
        assertTrue(preference.enabled.value)
        assertTrue(preference.stored())
        preference.setEnabled(false)
        assertFalse(preference.enabled.value)
        assertFalse(preference.stored())
    }

    @Test
    fun `not in the player settings sync payload and not the player settings store`() {
        assertFalse(PlayerSettingsStorage.exportToSyncPayload().containsKey(MATCH_DISPLAY_REFRESH_RATE_KEY))
        assertNotEquals("nuvio_player_settings", REFRESH_RATE_STORE)
        assertEquals("nuvio_refresh_rate", REFRESH_RATE_STORE)
        assertEquals("match_display_refresh_rate", MATCH_DISPLAY_REFRESH_RATE_KEY)
    }

    // Owner 2026-09-30: the on-screen badge can be switched off; it is ON unless the owner turned it off
    @Test
    fun `badge is ON when no file or key exists`() {
        val preference = preference()
        assertTrue(preference.badgeStored())
        assertTrue(preference.badgeEnabled.value)
        assertFalse(Files.exists(file), "reading must not create the file")
    }

    @Test
    fun `badge off survives a reload and does not touch the main switch`() {
        val preference = preference()
        preference.setBadgeEnabled(false)
        assertFalse(preference.badgeEnabled.value)
        val reloaded = preference()
        assertFalse(reloaded.badgeStored())
        assertFalse(reloaded.stored(), "the main switch stays at its default")
    }
}
