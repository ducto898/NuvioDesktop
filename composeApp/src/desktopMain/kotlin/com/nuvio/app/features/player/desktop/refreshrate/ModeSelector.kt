package com.nuvio.app.features.player.desktop.refreshrate

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
    fun select(fps: FpsResult.Snapped, current: DisplayMode, modes: List<DisplayMode>): Selection = TODO()

    /** The whole decision for one playback start (SPEC P3-6..P3-15). */
    fun decide(
        enabled: Boolean,
        containerFps: Double?,
        estimatedFps: Double?,
        isImage: Boolean,
        current: DisplayMode,
        modes: List<DisplayMode>,
    ): Selection = TODO()
}
