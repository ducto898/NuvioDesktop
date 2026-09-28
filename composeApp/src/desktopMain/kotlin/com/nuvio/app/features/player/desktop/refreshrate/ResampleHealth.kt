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

/** A stretch of judged samples whose rate estimate was off, that ended by itself before the limit (Q28, Phase 7 data). */
data class RateStreak(val samples: Int, val worstFps: Double)

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
class ResampleHealth(
    private val target: Rational,
    private val startedAt: Double,
    private val onRateStreak: (RateStreak) -> Unit = {},
) {
    private val samples = ArrayDeque<TimingSample>()

    /** Judged samples in a row whose rate estimate was off; one stall bends it for a sample or two. */
    private var rateErrors = 0
    private var worstFps = 0.0

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
        val rateOff = estimate != null && rate > 0.0 && kotlin.math.abs(estimate / rate - 1.0) > MAX_RATE_ERROR
        if (rateOff) {
            if (rateErrors == 0 || kotlin.math.abs(estimate!! - rate) > kotlin.math.abs(worstFps - rate)) worstFps = estimate!!
            rateErrors++
        } else {
            if (rateErrors > 0) onRateStreak(RateStreak(rateErrors, worstFps))
            rateErrors = 0
        }
        if (rateOff && rateErrors >= RATE_ERROR_SAMPLES) {
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

        /** ...in this many judged samples in a row (≈ 5 s at the 1 s watch; owner Q28). */
        const val RATE_ERROR_SAMPLES = 5
    }
}
