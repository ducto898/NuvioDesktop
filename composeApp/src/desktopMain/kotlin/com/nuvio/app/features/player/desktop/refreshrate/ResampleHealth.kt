package com.nuvio.app.features.player.desktop.refreshrate

/** One read of the player's mpv counters; [at] in seconds on any monotonic clock. */
data class TimingSample(
    val at: Double,
    val drops: Long,
    val mistimed: Long,
    val estimatedDisplayFps: Double?,
    val timePos: Double?,
    val paused: Boolean,
)

sealed interface HealthVerdict {
    /** Not enough samples yet, or the window can't be judged (pause, seek, buffering, counter reset). */
    data object Wait : HealthVerdict

    data object Healthy : HealthVerdict

    data class Unhealthy(val detail: String) : HealthVerdict
}

/**
 * Health of display-synced timing for one playback start (SPEC P5-11, Q21). Catches only clear breakage,
 * e.g. the display-resample collapse under a driver frame cap (Phase 2b), never normal runs.
 */
class ResampleHealth(private val target: Rational, private val startedAt: Double) {
    fun add(sample: TimingSample): HealthVerdict {
        // TODO(P5-11): implemented in commit B.
        return HealthVerdict.Wait
    }

    companion object {
        const val IGNORE_FIRST_SECONDS = 5.0
        const val WINDOW_SECONDS = 10.0

        /** A judged window's playback advance must lie in this range (excludes pause, buffering and seeks). */
        const val MIN_ADVANCE_SECONDS = 8.0
        const val MAX_ADVANCE_SECONDS = 12.0

        /** More drops + mistimed frames than this in one window is unhealthy. */
        const val MAX_BAD_FRAMES = 20L

        /** A larger relative difference between mpv's measured display rate and the target is unhealthy. */
        const val MAX_RATE_ERROR = 0.01
    }
}
