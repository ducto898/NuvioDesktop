package com.nuvio.app.core.storage

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
    override fun encode(plain: ByteArray): ByteArray = TODO("Phase 9 S1 commit B")

    override fun decode(stored: ByteArray): ByteArray? = TODO("Phase 9 S1 commit B")

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

    fun codecFor(name: String): StoreCodec? = TODO("Phase 9 S1 commit B")
}
