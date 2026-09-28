package com.nuvio.app.features.settings

import kotlinx.coroutines.flow.StateFlow

/**
 * nuvio-rr fork: "Match display refresh rate" (SPEC Phase 6). Stored per PC, not per profile and not synced (Q23);
 * only Windows has it. A change applies from the next playback start (Q25).
 */
internal expect object RefreshRateMatchSetting {
    val available: Boolean
    val enabled: StateFlow<Boolean>

    fun setEnabled(value: Boolean)
}
