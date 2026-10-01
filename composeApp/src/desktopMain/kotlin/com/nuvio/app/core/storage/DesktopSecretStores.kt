package com.nuvio.app.core.storage

import com.sun.jna.platform.win32.Crypt32Util

/**
 * nuvio-rr fork, Phase 9 S1. Account sessions, Trakt/Simkl/MDBList tokens, debrid and other API keys (also inside
 * addon URLs and cached stream links) were stored as plain text. On Windows these stores are encrypted with DPAPI
 * for the current Windows account: another account or a copied disk can't read them (code running as the same user
 * still can; that is DPAPI's limit). A plain-text file still loads and is encrypted on its next save.
 */
internal interface StoreCodec {
    fun encode(plain: ByteArray): ByteArray

    /** The plain bytes, or null when the file can't be decrypted on this account (then the store starts empty). */
    fun decode(stored: ByteArray): ByteArray?
}

internal class DpapiStoreCodec : StoreCodec {
    override fun encode(plain: ByteArray): ByteArray = MAGIC + Crypt32Util.cryptProtectData(plain)

    override fun decode(stored: ByteArray): ByteArray? {
        if (stored.size < MAGIC.size || !stored.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) return stored  // plain text
        return runCatching { Crypt32Util.cryptUnprotectData(stored.copyOfRange(MAGIC.size, stored.size)) }.getOrNull()
    }

    companion object {
        val MAGIC: ByteArray = "NUVIO-DPAPI-1\n".toByteArray(Charsets.US_ASCII)
    }
}

internal object DesktopSecretStores {
    private val names = setOf(
        "nuvio_auth",
        "nuvio_trakt_auth",
        "nuvio_simkl_auth",
        "nuvio_mdblist_auth",
        "nuvio_mdblist_settings",
        "nuvio_debrid_settings",
        "nuvio_tmdb_settings",
        "nuvio_addons",
        "nuvio_stream_link_cache",
    )

    private val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
    private val dpapi by lazy { DpapiStoreCodec() }

    fun codecFor(name: String): StoreCodec? = if (isWindows && name in names) dpapi else null
}
