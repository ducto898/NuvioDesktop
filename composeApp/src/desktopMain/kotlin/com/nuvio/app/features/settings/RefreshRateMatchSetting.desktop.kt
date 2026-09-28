package com.nuvio.app.features.settings

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.isWindows
import kotlinx.coroutines.flow.StateFlow

/** Per-PC store (Q23): its own file, keys not wrapped by ProfileScopedKey, not in any sync payload. */
internal const val REFRESH_RATE_STORE = "nuvio_refresh_rate"
internal const val MATCH_DISPLAY_REFRESH_RATE_KEY = "match_display_refresh_rate"

internal actual object RefreshRateMatchSetting {
    private val preference by lazy { RefreshRateMatchPreference(DesktopStorage.store(REFRESH_RATE_STORE)) }

    actual val available: Boolean
        get() = isWindows

    actual val enabled: StateFlow<Boolean>
        get() = preference.enabled

    actual fun setEnabled(value: Boolean) = preference.setEnabled(value)

    /** Read by the native H2 upcall at each playback start (P6-9). */
    fun stored(): Boolean = preference.stored()
}

/** The setting over one store; default OFF when the file or the key is missing. */
internal class RefreshRateMatchPreference(private val store: DesktopStorage.Store) {
    val enabled: StateFlow<Boolean>
        get() = TODO("Phase 6 commit B")

    fun setEnabled(value: Boolean): Unit = TODO("Phase 6 commit B")

    fun stored(): Boolean = TODO("Phase 6 commit B")
}
