package com.nuvio.app.features.player.desktop.refreshrate.runtime

import com.nuvio.app.features.player.desktop.refreshrate.DisplayMode
import com.nuvio.app.features.player.desktop.refreshrate.DisplayState
import com.nuvio.app.features.player.desktop.refreshrate.SwitchOutcome
import com.nuvio.app.features.player.desktop.refreshrate.Timing

/** What the native glue reads at mpv's on_preloaded hook for one playback start (SPEC P4-9). */
data class StartInput(
    val playerId: Long,
    val display: String,
    val containerFps: Double?,
    val estimatedFps: Double?,
    val isImage: Boolean,
    /** NVIDIA Max Frame Rate for this process (P5-9); null = off or unknown. */
    val frameCap: Double? = null,
)

/** A player's mpv counters for the health check (P5-11). [displaySyncApplied]: our timing change is in place. */
data class TimingStats(
    val drops: Long,
    val mistimed: Long,
    val estimatedDisplayFps: Double?,
    val timePos: Double?,
    val paused: Boolean,
    val displaySyncApplied: Boolean,
)

/**
 * The Win32 side (SPEC P4-5..P4-8). Only the `nuvio-rr` thread calls it, one call at a time.
 * Implementations may throw; the controller turns that into `unexpected-error`.
 */
interface DisplayPort {
    /** Current state of [display]; null = display not found. */
    fun query(display: String): DisplayState?

    /** Modes of [display] with exact rates; null = enumeration failed. */
    fun modes(display: String): List<DisplayMode>?

    /** CDS_FULLSCREEN switch, then settle. [playerId] stopping aborts the settle (`stop-requested`). */
    fun switchTo(display: String, mode: DisplayMode, playerId: Long): SwitchOutcome

    /** Back to the registry mode; false = the API failed. */
    fun restore(display: String): Boolean

    /** Display hosting [playerId]'s window now; null when that player is gone. */
    fun playerDisplay(playerId: Long): String?

    /** Applies [timing] to [playerId]'s mpv (P5-6); false = player gone/stopping or an mpv call failed. */
    fun setTiming(playerId: Long, timing: Timing): Boolean

    /** [playerId]'s mpv counters; null when that player is gone. */
    fun timingStats(playerId: Long): TimingStats?
}
