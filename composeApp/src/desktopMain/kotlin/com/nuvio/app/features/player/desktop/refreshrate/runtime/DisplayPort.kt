package com.nuvio.app.features.player.desktop.refreshrate.runtime

import com.nuvio.app.features.player.desktop.refreshrate.*

/** What the native glue reads at mpv's on_preloaded hook for one playback start (SPEC P4-9). */
data class StartInput(
    val playerId: Long,
    val display: String,
    val containerFps: Double?,
    val estimatedFps: Double?,
    val isImage: Boolean,
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
}
