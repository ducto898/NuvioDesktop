package com.nuvio.app.features.player.desktop.refreshrate.runtime

import com.nuvio.app.features.player.desktop.refreshrate.*

/**
 * Serialises every [RefreshRateController] call on one thread and runs the display watcher
 * there (SPEC P4-13, P4-16). Callers never block longer than the timeout they pass.
 */
class RefreshRateDispatcher(
    private val controller: RefreshRateController,
    private val log: (String) -> Unit,
    threadName: String = "nuvio-rr",
    watchPeriodMs: Long = 1000,
) : AutoCloseable {
    /** Blocks at most [timeoutMs]; null on timeout (the start still completes on the thread). */
    fun playbackStart(input: StartInput, timeoutMs: Long): Timing? = TODO("Phase 4 commit B")

    /** Returns at once. */
    fun screenGone(): Unit = TODO("Phase 4 commit B")

    /** Waits at most [timeoutMs] for the restore; true if it finished in time. */
    fun appExit(timeoutMs: Long): Boolean = TODO("Phase 4 commit B")

    override fun close(): Unit = TODO("Phase 4 commit B")
}
