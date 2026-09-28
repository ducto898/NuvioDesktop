package com.nuvio.app.features.player.desktop.refreshrate.runtime

/** Where the on/off answer for one playback start came from (SPEC P6-7, Q24). */
internal enum class EnableSource { Env, Setting }

internal data class Enablement(val enabled: Boolean, val source: EnableSource) {
    /** Result of the native H2 upcall: 0 = off, 1 = on by the env override, 2 = on by the setting (-1 = error). */
    val nativeCode: Int
        get() = TODO("Phase 6 commit B")
}

/**
 * `NUVIO_RR_ENABLE`: "1" forces on, "0" forces off (dev and measure runs); anything else, or unset, defers to the
 * stored setting, which is only read in that case.
 */
internal fun resolveEnablement(env: String?, setting: () -> Boolean): Enablement = TODO("Phase 6 commit B")

/** [Enablement.nativeCode] for the native H2 upcall; anything thrown while deciding is -1, which native treats as off (P6-10). */
internal fun enablementCode(env: String?, setting: () -> Boolean): Int = TODO("Phase 6 commit B")
