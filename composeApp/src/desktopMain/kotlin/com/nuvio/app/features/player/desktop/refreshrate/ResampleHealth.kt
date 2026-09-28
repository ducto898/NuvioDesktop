package com.nuvio.app.features.player.desktop.refreshrate

import java.util.Locale
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
    private val samples = ArrayDeque<TimingSample>()

    /** Judges the 10 s window that ends at [sample]. */
    fun add(sample: TimingSample): HealthVerdict {
        if (sample.at < startedAt + IGNORE_FIRST_SECONDS) return HealthVerdict.Wait
        samples.addLast(sample)
        // Keep one sample at or before the window start as the base.
        while (samples.size > 2 && samples[1].at <= sample.at - WINDOW_SECONDS) samples.removeFirst()
        val base = samples.first().takeIf { it.at <= sample.at - WINDOW_SECONDS } ?: return HealthVerdict.Wait
        if (sample.paused || base.paused) return HealthVerdict.Wait
        val advance = (sample.timePos ?: return HealthVerdict.Wait) - (base.timePos ?: return HealthVerdict.Wait)
        if (advance < MIN_ADVANCE_SECONDS || advance > MAX_ADVANCE_SECONDS) return HealthVerdict.Wait
        val drops = sample.drops - base.drops
        val mistimed = sample.mistimed - base.mistimed
        if (drops < 0 || mistimed < 0) return HealthVerdict.Wait
        val window = "%.0f s".format(Locale.ROOT, sample.at - base.at)
        if (drops + mistimed > MAX_BAD_FRAMES) {
            return HealthVerdict.Unhealthy("drops=$drops mistimed=$mistimed in $window (max $MAX_BAD_FRAMES)")
        }
        val estimate = sample.estimatedDisplayFps
        val rate = target.toDouble()
        if (estimate != null && rate > 0.0 && kotlin.math.abs(estimate / rate - 1.0) > MAX_RATE_ERROR) {
            return HealthVerdict.Unhealthy(
                "estimated-display-fps=${"%.3f".format(Locale.ROOT, estimate)} vs target ${"%.3f".format(Locale.ROOT, rate)} (max ${MAX_RATE_ERROR * 100} %)",
            )
        }
        return HealthVerdict.Healthy
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
