package com.nuvio.app.core.storage

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// nuvio-rr fork, Phase 9 S1: tokens and API keys are stored encrypted with Windows DPAPI.
class DesktopSecretStoresTest {
    private val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    private fun bytesContain(haystack: ByteArray, needle: String): Boolean =
        String(haystack, Charsets.ISO_8859_1).contains(needle)

    @Test
    fun `a secret store is written encrypted and reads back`() {
        if (!isWindows) return
        val file = Files.createTempDirectory("secret").resolve("nuvio_trakt_auth.properties")
        DesktopStorage.Store(file, codec = DpapiStoreCodec()).putString("trakt_auth_payload_1", "secret-token-123")
        val raw = Files.readAllBytes(file)
        assertTrue(raw.size > DpapiStoreCodec.MAGIC.size && raw.copyOfRange(0, DpapiStoreCodec.MAGIC.size).contentEquals(DpapiStoreCodec.MAGIC))
        assertFalse(bytesContain(raw, "secret-token-123"), "the token must not be on disk in plain text")
        assertEquals("secret-token-123", DesktopStorage.Store(file, codec = DpapiStoreCodec()).getString("trakt_auth_payload_1"))
    }

    @Test
    fun `an existing plain-text store still loads and is encrypted on its next save`() {
        if (!isWindows) return
        val file = Files.createTempDirectory("secret").resolve("nuvio_debrid_settings.properties")
        DesktopStorage.Store(file).putString("api_key", "plain-key")
        val store = DesktopStorage.Store(file, codec = DpapiStoreCodec())
        assertEquals("plain-key", store.getString("api_key"))
        store.putString("other", "x")
        assertFalse(bytesContain(Files.readAllBytes(file), "plain-key"))
        assertEquals("plain-key", DesktopStorage.Store(file, codec = DpapiStoreCodec()).getString("api_key"))
    }

    @Test
    fun `a file this account can't decrypt loads empty instead of failing`() {
        if (!isWindows) return
        val file = Files.createTempDirectory("secret").resolve("nuvio_auth.properties")
        Files.write(file, DpapiStoreCodec.MAGIC + ByteArray(64) { 7 })
        val store = DesktopStorage.Store(file, codec = DpapiStoreCodec())
        assertNull(store.getString("anything"))
        store.putString("k", "v")  // and it can be written again
        assertEquals("v", DesktopStorage.Store(file, codec = DpapiStoreCodec()).getString("k"))
    }

    @Test
    fun `only the secret stores are encrypted`() {
        if (!isWindows) return
        for (name in listOf("nuvio_auth", "nuvio_trakt_auth", "nuvio_simkl_auth", "nuvio_mdblist_auth", "nuvio_debrid_settings", "nuvio_addons")) {
            assertNotNull(DesktopSecretStores.codecFor(name), name)
        }
        assertNull(DesktopSecretStores.codecFor("nuvio_theme_settings"))
        assertNull(DesktopSecretStores.codecFor("nuvio_watch_progress"))
    }
}
