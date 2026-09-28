package com.nuvio.app.features.player.desktop.refreshrate.runtime

import com.nuvio.app.features.player.desktop.refreshrate.*

/**
 * Runs the pure session (Phase 3) against a [DisplayPort] (SPEC P4-13). Not thread-safe:
 * [RefreshRateDispatcher] calls it from one thread only.
 */
class RefreshRateController(
    private val port: DisplayPort,
    private val log: (String) -> Unit,
) {
    var session: Session = Session()
        private set

    /** One playback start: decide, switch, verify. Returns the timing for that player. */
    fun playbackStart(input: StartInput): Timing = TODO("Phase 4 commit B")

    /** The player screen went away (H6), or the player moved to another monitor. */
    fun screenGone(reason: String = "screen-gone"): Unit = TODO("Phase 4 commit B")

    /** Window close / JVM shutdown (H8): restore, and never switch again. */
    fun appExit(): Unit = TODO("Phase 4 commit B")

    /** One display-watcher tick (P4-16, Q15). */
    fun watch(): Unit = TODO("Phase 4 commit B")
}
