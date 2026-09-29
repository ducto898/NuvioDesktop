package com.nuvio.app.features.player.desktop.refreshrate.runtime

import com.nuvio.app.features.player.desktop.refreshrate.AT_240
import com.nuvio.app.features.player.desktop.refreshrate.DisplayState
import com.nuvio.app.features.player.desktop.refreshrate.MODE_240
import com.nuvio.app.features.player.desktop.refreshrate.MODE_280
import com.nuvio.app.features.player.desktop.refreshrate.SessionState
import com.nuvio.app.features.player.desktop.refreshrate.SwitchOutcome
import com.nuvio.app.features.player.desktop.refreshrate.Timing
import com.nuvio.app.features.player.desktop.refreshrate.qhd
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// SPEC P5-7 (timing routed to the session owner), P5-10 (frame cap), P5-11 (health fallback).
class RefreshRateTimingTest {
    private val port = FakeDisplayPort()
    private val lines = mutableListOf<String>()
    private var now = 0.0
    private val controller = RefreshRateController(port) { lines += it }.also { it.clock = { now } }

    private val sync240 = "setTiming p1 display-sync(239901/1000)"
    private val upstream = "setTiming p1 upstream"

    private fun logged(text: String) = lines.any { text in it }

    private fun timingCalls() = port.callsNamed("setTiming")

    private fun switched() {
        assertEquals(Timing.DisplaySync(MODE_240.refresh), controller.playbackStart(startInput(1)))
        port.calls.clear()
    }

    /** Two watcher ticks with the display reading [state] (the watcher's two-read debounce). */
    private fun displayReads(state: DisplayState) {
        port.states["A"] = state
        controller.watch()
        controller.watch()
    }

    private fun stats(
        drops: Long = 0,
        mistimed: Long = 0,
        est: Double? = MODE_240.refresh.toDouble(),
        pos: Double? = now,
        ours: Boolean = true,
    ) = TimingStats(drops, mistimed, est, pos, paused = false, displaySyncApplied = ours)

    /** Advances the clock 1 s per tick for [seconds] ticks, the player reporting [at] each time. */
    private fun play(seconds: Int, player: Long = 1, at: () -> TimingStats = { stats() }) {
        repeat(seconds) {
            now += 1.0
            port.stats[player] = at()
            controller.watch()
        }
    }

    // P5-7: the hook's timing goes back to the worker, not through setTiming
    @Test
    fun `a start returns its timing to the hook and makes no setTiming call`() {
        assertEquals(Timing.DisplaySync(MODE_240.refresh), controller.playbackStart(startInput(1)))
        assertEquals(emptyList(), timingCalls())
        assertEquals(Timing.DisplaySync(MODE_240.refresh), controller.playbackStart(startInput(2, fps = 24.0)))
        assertEquals(emptyList(), timingCalls(), "same-target next episode: the hook applies it")
    }

    @Test
    fun `mode lost and switched back gives the owner display-sync again`() {
        switched()
        displayReads(DisplayState(MODE_280, hdr = true))
        assertTrue(logged("mode-lost"))
        assertEquals(listOf(sync240), timingCalls())
    }

    @Test
    fun `mode lost a second time puts the owner back on upstream timing`() {
        switched()
        displayReads(DisplayState(MODE_280, hdr = true))
        port.calls.clear()
        displayReads(DisplayState(MODE_280, hdr = true))
        assertTrue(logged("mode-lost-again"))
        assertEquals(listOf(upstream), timingCalls())
    }

    @Test
    fun `an HDR toggle re-switch gives display-sync, and upstream once every re-switch is used up`() {
        switched()
        var hdr = true
        // 3 HDR re-switches (P4-22) + the one mode-lost re-switch (P3-22) all end switched
        repeat(4) {
            hdr = !hdr
            port.calls.clear()
            displayReads(DisplayState(qhd(279961, 1000, bpc = if (hdr) 10 else 8), hdr = hdr))
            assertEquals(listOf("setTiming p1 display-sync(239901/1000)"), timingCalls(), "re-switch ${it + 1}")
        }
        hdr = !hdr
        port.calls.clear()
        displayReads(DisplayState(qhd(279961, 1000, bpc = if (hdr) 10 else 8), hdr = hdr))
        assertEquals(listOf(upstream), timingCalls())
        assertEquals(SessionState.Idle, controller.session.state)
    }

    @Test
    fun `an HDR change at the same rate touches no timing`() {
        switched()
        displayReads(DisplayState(MODE_240, hdr = false))
        assertTrue(logged("hdr-changed"))
        assertEquals(emptyList(), timingCalls())
    }

    @Test
    fun `a re-switch that fails verify restores and puts the owner on upstream timing`() {
        switched()
        port.onSwitch = { _, _, _ -> SwitchOutcome.Ok(DisplayState(MODE_280, hdr = true)) }
        displayReads(DisplayState(MODE_280, hdr = true))
        assertTrue(logged("verify-mismatch"))
        assertEquals(listOf(upstream), timingCalls())
        assertEquals(SessionState.Idle, controller.session.state)
    }

    @Test
    fun `a player moved to another monitor goes back to upstream timing (Q15)`() {
        switched()
        port.playerDisplays[1] = "B"
        controller.watch()
        assertTrue(logged("window-moved"))
        assertEquals(listOf(upstream), timingCalls())
    }

    @Test
    fun `screen gone puts the owner back on upstream timing, app exit needs no timing call`() {
        // Phase 7 review #5: the owner may still be playing (another surface); a gone player just answers false
        switched()
        controller.screenGone()
        assertEquals(listOf(upstream), timingCalls())
        switched()
        controller.appExit()
        assertEquals(emptyList(), timingCalls())
    }

    @Test
    fun `a failing or throwing setTiming is logged and leaves the session as the step left it`() {
        for (setTiming in listOf<(Long, Timing) -> Boolean>({ _, _ -> false }, { _, _ -> error("jni") })) {
            val port = FakeDisplayPort().apply { onSetTiming = setTiming }
            val lines = mutableListOf<String>()
            val controller = RefreshRateController(port) { lines += it }
            controller.playbackStart(startInput(1))
            port.states["A"] = DisplayState(MODE_280, hdr = true)
            controller.watch()
            controller.watch()
            assertIs<SessionState.Switched>(controller.session.state)
            assertTrue(lines.any { "setTiming" in it && "failed" in it }, "$lines")
        }
    }

    // P5-10 through the controller
    @Test
    fun `a capped driver means no switch and upstream timing`() {
        assertEquals(Timing.Upstream, controller.playbackStart(startInput(1, frameCap = 200.0)))
        assertEquals(emptyList(), port.callsNamed("switch"))
        assertTrue(logged("frame-cap"))
    }

    // P5-11 (Q21): health fallback
    @Test
    fun `collapsed display-resample falls back to upstream timing once and keeps the mode`() {
        switched()
        play(30) { stats(est = 6.5) }
        assertEquals(listOf(upstream), timingCalls())
        assertTrue(logged("resample-unhealthy"))
        assertEquals(emptyList(), port.callsNamed("switch"))
        assertEquals(emptyList(), port.callsNamed("restore"))
        assertIs<SessionState.Switched>(controller.session.state)
        assertEquals(AT_240, port.states["A"])
        port.calls.clear()
        play(30) { stats(est = 6.5) }
        assertEquals(emptyList(), port.callsNamed("timingStats"), "stops watching after the fallback")
    }

    @Test
    fun `healthy display-resample is left alone`() {
        switched()
        play(60)
        assertEquals(emptyList(), timingCalls())
        assertTrue(port.callsNamed("timingStats").isNotEmpty())
    }

    @Test
    fun `too many bad frames also falls back`() {
        switched()
        var bad = 0L
        play(30) { bad += 3; stats(drops = bad) }
        assertEquals(listOf(upstream), timingCalls())
    }

    @Test
    fun `upstream timing at the start means no health reads`() {
        controller.playbackStart(startInput(1, fps = null))
        play(30)
        assertEquals(emptyList(), port.callsNamed("timingStats"))
    }

    @Test
    fun `display-sync without a switch (already at 240) is watched too`() {
        port.states["A"] = AT_240
        assertEquals(Timing.DisplaySync(MODE_240.refresh), controller.playbackStart(startInput(1)))
        play(30) { stats(est = 6.5) }
        assertEquals(listOf(upstream), timingCalls())
    }

    @Test
    fun `a gone player or one without our timing stops the health reads`() {
        switched()
        now += 1.0
        controller.watch() // no stats for p1 ⇒ gone
        port.calls.clear()
        play(20)
        assertEquals(emptyList(), port.callsNamed("timingStats"))

        val port2 = FakeDisplayPort()
        val c2 = RefreshRateController(port2) {}.also { it.clock = { now } }
        c2.playbackStart(startInput(1))
        port2.stats[1] = stats(est = 6.5, ours = false) // native could not apply it (timing-failed)
        repeat(30) { now += 1.0; c2.watch() }
        assertEquals(emptyList(), port2.callsNamed("setTiming"))
    }

    @Test
    fun `the health watch follows the newest display-sync player`() {
        switched()
        assertEquals(Timing.DisplaySync(MODE_240.refresh), controller.playbackStart(startInput(2, fps = 24.0)))
        port.calls.clear()
        play(30, player = 2) { stats(est = 6.5) }
        assertEquals(listOf("setTiming p2 upstream"), timingCalls())
        assertEquals(emptyList(), port.callsNamed("timingStats p1"))
    }

    @Test
    fun `after mode-lost-again there are no health reads`() {
        switched()
        displayReads(DisplayState(MODE_280, hdr = true))
        displayReads(DisplayState(MODE_280, hdr = true))
        port.calls.clear()
        play(20)
        assertEquals(emptyList(), port.callsNamed("timingStats"))
    }

    @Test
    fun `a throwing stats read never escapes the watcher`() {
        switched()
        port.onTimingStats = { error("jni") }
        play(3)
        assertTrue(logged("unexpected-error"))
    }

    // Found in the first Phase 5 run: after the app-exit restore the display is back at 280, so mpv's rate
    // estimate drops and the health check must not judge (or touch) the closing player.
    @Test
    fun `app exit and screen gone stop the health watch`() {
        switched()
        controller.appExit()
        port.calls.clear()
        play(30) { stats(est = 226.0) }
        assertEquals(emptyList(), port.callsNamed("timingStats"), "after app exit")
        assertEquals(emptyList(), timingCalls())

        val port2 = FakeDisplayPort()
        val c2 = RefreshRateController(port2) {}.also { it.clock = { now } }
        c2.playbackStart(startInput(1))
        c2.screenGone()
        port2.calls.clear()
        repeat(30) { now += 1.0; port2.stats[1] = stats(est = 226.0); c2.watch() }
        assertEquals(emptyList(), port2.callsNamed("timingStats"), "after screen gone")
    }

    // Found in the P5-8 drop-mode run: a display change makes mpv's rate estimate dip for a few seconds, and the
    // health check fired before the watcher's two-read debounce saw the change. It must only judge while the display
    // reads the target, unchanged, for a whole window.
    @Test
    fun `a lost mode is handled by the re-switch, never by the health check`() {
        switched()
        play(20) // as in the run: the drop comes after the first judged window
        port.states["A"] = DisplayState(MODE_280, hdr = true) // monitor off/on
        play(1) { stats(est = 220.0) }
        play(1) { stats(est = 220.0) } // second read: mode-lost re-switch to 240
        play(3) { stats(est = 225.0) } // estimate still recovering
        play(30)
        assertTrue(logged("mode-lost"))
        assertTrue(!logged("resample-unhealthy"), "$lines")
        assertEquals(listOf(sync240), timingCalls())
    }

    @Test
    fun `an HDR toggle at the same rate does not trip the health check`() {
        switched()
        play(12)
        port.states["A"] = DisplayState(MODE_240, hdr = false)
        play(4) { stats(est = 200.0) } // blank + re-sync after the toggle
        play(30)
        assertTrue(logged("hdr-changed"))
        assertTrue(!logged("resample-unhealthy"), "$lines")
        assertEquals(emptyList(), timingCalls())
    }

    @Test
    fun `a stall that bends the rate estimate for a moment is logged and keeps display sync (Q28)`() {
        switched()
        play(25)
        play(2) { stats(est = 235.649) } // measured 2026-09-28 23:23:12, one 1.3 s stall
        play(20)
        assertEquals(emptyList(), timingCalls(), "$lines")
        assertTrue(!logged("resample-unhealthy"), "$lines")
        assertTrue(lines.any { "rate-off p1 2 samples" in it && "235.649" in it && "recovered" in it }, "$lines")
    }

    @Test
    fun `a fullscreen toggle that bends the rate estimate for 7 samples keeps display sync (Phase 7)`() {
        switched()
        play(25)
        play(7) { stats(est = 235.0) } // measured 2026-09-29: f11 at 143.973 ⇒ 141.43 (-1.8 %) for 7 samples
        play(20)
        assertEquals(emptyList(), timingCalls(), "$lines")
        assertTrue(!logged("resample-unhealthy"), "$lines")
        assertTrue(lines.any { "rate-off p1 7 samples" in it && "recovered" in it }, "$lines")
    }

    @Test
    fun `broken timing on a steady display is still caught`() {
        switched()
        play(12)
        port.states["A"] = DisplayState(MODE_240, hdr = false)
        play(2)
        play(30) { stats(est = 6.5) } // stays broken after the display settled
        assertEquals(listOf(upstream), timingCalls())
    }
}
