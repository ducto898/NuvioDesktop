package com.nuvio.app.features.player.desktop.refreshrate

import kotlin.math.abs
import kotlin.math.roundToLong

sealed interface Selection {
    val reason: String

    /** Switch to [target], [multiple] refreshes per video frame. */
    data class Switch(
        val target: DisplayMode,
        val multiple: Long,
        val errorPpm: Double,
        val fps: FpsResult.Snapped,
        val considered: Int,
    ) : Selection {
        override val reason: String get() = "switch"
    }

    /** The current mode already is the best target: display-synced timing without a switch. */
    data class AlreadyAtTarget(
        val mode: DisplayMode,
        val multiple: Long,
        val errorPpm: Double,
        val fps: FpsResult.Snapped,
        val considered: Int,
    ) : Selection {
        override val reason: String get() = "already-at-target"
    }

    data class NoSwitch(override val reason: String, val detail: String) : Selection
}

object ModeSelector {
    /** Relative tolerance between a mode and k x fps. */
    const val MODE_TOLERANCE = 0.001

    /** Highest integer multiple of [fps] among [modes] compatible with [current] (SPEC P3-10..P3-14). */
    fun select(fps: FpsResult.Snapped, current: DisplayMode, modes: List<DisplayMode>): Selection {
        if (!fps.rate.isValid || !current.refresh.isValid) {
            return Selection.NoSwitch("no-suitable-mode", "fps=${fps.rate} current=${current.describe()}: invalid rate")
        }
        val candidates = mutableListOf<DisplayMode>()
        for (m in modes) {
            val compatible = m.width == current.width && m.height == current.height &&
                m.bitsPerColor == current.bitsPerColor && !m.interlaced && m.refresh.isValid
            if (compatible && candidates.none { it.refresh.sameAs(m.refresh) }) candidates += m
        }

        val f = fps.rate.toDouble()
        var best: DisplayMode? = null
        var bestK = 0L
        var bestError = 0.0
        for (m in candidates) {
            val ratio = m.refresh.toDouble() / f
            if (!ratio.isFinite() || ratio < 0.5) continue
            val k = ratio.roundToLong()
            val error = m.refresh.toDouble() / (k.toDouble() * f) - 1.0
            if (!(abs(error) <= MODE_TOLERANCE)) continue
            if (best == null || k > bestK || (k == bestK && abs(error) < abs(bestError))) {
                best = m
                bestK = k
                bestError = error
            }
        }

        if (best == null) {
            val considered = candidates.joinToString { it.refresh.toString() }
            return Selection.NoSwitch(
                "no-suitable-mode",
                "fps=${fps.rate} (${fps.source.name.lowercase()} ${fps.input}) current=${current.describe()} " +
                    "considered=[$considered] none within ${MODE_TOLERANCE * 100}% of k x fps",
            )
        }
        val ppm = bestError * 1e6
        return if (best.refresh.sameAs(current.refresh)) {
            Selection.AlreadyAtTarget(best, bestK, ppm, fps, candidates.size)
        } else {
            Selection.Switch(best, bestK, ppm, fps, candidates.size)
        }
    }

    /** The whole decision for one playback start (SPEC P3-6..P3-15). */
    fun decide(
        enabled: Boolean,
        containerFps: Double?,
        estimatedFps: Double?,
        isImage: Boolean,
        current: DisplayMode,
        modes: List<DisplayMode>,
    ): Selection {
        if (!enabled) return Selection.NoSwitch("disabled", "setting off")
        return when (val fps = FpsSnapper.resolve(containerFps, estimatedFps, isImage)) {
            is FpsResult.Rejected -> Selection.NoSwitch(fps.reason, "container=${fps.containerFps} estimate=${fps.estimatedFps}")
            is FpsResult.Snapped -> select(fps, current, modes)
        }
    }
}
