package com.nuvio.app.features.player.desktop.refreshrate.runtime

/** Where the on/off answer for one playback start came from (SPEC P6-7, Q24). */
internal enum class EnableSource { Env, Setting }

internal data class Enablement(val enabled: Boolean, val source: EnableSource) {
    /** Result of the native H2 upcall: 0 = off, 1 = on by the env override, 2 = on by the setting (-1 = error). */
    val nativeCode: Int
        get() = when {
            !enabled -> 0
            source == EnableSource.Env -> 1
            else -> 2
        }
}

/**
 * `NUVIO_RR_ENABLE`: "1" forces on, "0" forces off (dev and measure runs); anything else, or unset, defers to the
 * stored setting, which is only read in that case.
 */
internal fun resolveEnablement(env: String?, setting: () -> Boolean): Enablement = when (env) {
    "1" -> Enablement(true, EnableSource.Env)
    "0" -> Enablement(false, EnableSource.Env)
    else -> Enablement(setting(), EnableSource.Setting)
}

/** Owner 2026-09-30: the on-screen badge. `NUVIO_RR_OSD` "0"/"1" override; else the setting; an error shows none. */
internal fun resolveBadge(env: String?, setting: () -> Boolean): Boolean = when (env) {
    "1" -> true
    "0" -> false
    else -> try {
        setting()
    } catch (t: Throwable) {
        false
    }
}

/** [Enablement.nativeCode] for the native H2 upcall; anything thrown while deciding is -1, which native treats as off (P6-10). */
internal fun enablementCode(env: String?, setting: () -> Boolean): Int = try {
    resolveEnablement(env, setting).nativeCode
} catch (t: Throwable) {
    -1
}
