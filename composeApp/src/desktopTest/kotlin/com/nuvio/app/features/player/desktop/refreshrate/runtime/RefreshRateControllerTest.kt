package com.nuvio.app.features.player.desktop.refreshrate.runtime

import com.nuvio.app.features.player.desktop.refreshrate.AT_240
import com.nuvio.app.features.player.desktop.refreshrate.DisplayState
import com.nuvio.app.features.player.desktop.refreshrate.FailureKind
import com.nuvio.app.features.player.desktop.refreshrate.MODE_100
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

class RefreshRateControllerTest {
    private val port = FakeDisplayPort()
    private val lines = mutableListOf<String>()
    private val controller = RefreshRateController(port) { lines += it }

    private fun logged(text: String) = lines.any { text in it }

    private fun switched() {
        assertEquals(Timing.DisplaySync(MODE_240.refresh), controller.playbackStart(startInput(1)))
        port.calls.clear()
    }

    // P4-13: start → decide → switch → verify → display-sync
    @Test
    fun `a 23_976 start switches to 239_901 and returns display-sync`() {
        val timing = controller.playbackStart(startInput(1))
        assertEquals(Timing.DisplaySync(MODE_240.refresh), timing)
        assertEquals(listOf("switch A 239901/1000 p1"), port.callsNamed("switch"))
        val ctx = assertIs<SessionState.Switched>(controller.session.state).context
        assertEquals(DisplayState(MODE_280, hdr = true), ctx.original)
        assertEquals(AT_240, port.states["A"])
    }

    @Test
    fun `25 fps switches to 100`() {
        assertEquals(Timing.DisplaySync(MODE_100.refresh), controller.playbackStart(startInput(1, fps = 25.0)))
    }

    // P4-20: the values behind the reason
    @Test
    fun `the start line carries fps, source, k, target and the modes considered`() {
        controller.playbackStart(startInput(1))
        val line = lines.first { "start" in it }
        for (part in listOf("p1", "display=A", "fps=23.976", "snapped=24000/1001", "source=container", "k=10",
            "target=2560x1440@239901/1000", "considered=")) {
            assertTrue(part in line, "'$part' missing in: $line")
        }
    }

    @Test
    fun `no fps means no switch and upstream timing`() {
        assertEquals(Timing.Upstream, controller.playbackStart(startInput(1, fps = null)))
        assertEquals(emptyList(), port.callsNamed("switch"))
        assertTrue(logged("fps-missing"))
    }

    // P4-7 / P3-23: failures before a switch was attempted
    @Test
    fun `display not found keeps the current rate`() {
        assertEquals(Timing.Upstream, controller.playbackStart(startInput(1, display = "X")))
        assertEquals(emptyList(), port.callsNamed("switch"))
        assertEquals(emptyList(), port.callsNamed("restore"))
        assertTrue(logged("display-not-found"))
        assertEquals(SessionState.Idle, controller.session.state)
    }

    @Test
    fun `enumeration failure keeps the current rate`() {
        port.modeList = null
        assertEquals(Timing.Upstream, controller.playbackStart(startInput(1)))
        assertEquals(emptyList(), port.callsNamed("switch"))
        assertTrue(logged("enumerate-failed"))
    }

    @Test
    fun `an exception while reading the display is an unexpected error, never a crash`() {
        port.onQuery = { error("boom") }
        assertEquals(Timing.Upstream, controller.playbackStart(startInput(1)))
        assertTrue(logged("unexpected-error"))
        assertEquals(SessionState.Idle, controller.session.state)
    }

    // P4-7 / P3-23: failures after a switch was attempted restore
    @Test
    fun `every switch failure restores and returns upstream timing`() {
        for (kind in listOf(FailureKind.SWITCH_API_ERROR, FailureKind.SETTLE_TIMEOUT, FailureKind.STOP_REQUESTED,
            FailureKind.DISPLAY_NOT_FOUND, FailureKind.UNEXPECTED_ERROR)) {
            val port = FakeDisplayPort().apply { onSwitch = { _, _, _ -> SwitchOutcome.Failed(kind) } }
            val lines = mutableListOf<String>()
            val controller = RefreshRateController(port) { lines += it }
            assertEquals(Timing.Upstream, controller.playbackStart(startInput(1)), kind.code)
            assertEquals(listOf("restore A"), port.callsNamed("restore"), kind.code)
            assertEquals(SessionState.Idle, controller.session.state, kind.code)
            assertTrue(lines.any { kind.code in it }, kind.code)
        }
    }

    @Test
    fun `an exception from the switch is an unexpected error and restores`() {
        port.onSwitch = { _, _, _ -> throw IllegalStateException("jni") }
        assertEquals(Timing.Upstream, controller.playbackStart(startInput(1)))
        assertEquals(listOf("restore A"), port.callsNamed("restore"))
        assertTrue(logged("unexpected-error"))
    }

    // P3-21 + P4-20: verify mismatch logs observed vs target
    @Test
    fun `a switch that lands on the wrong rate restores and logs observed vs target`() {
        port.onSwitch = { _, _, _ -> SwitchOutcome.Ok(DisplayState(MODE_280, hdr = true)) }
        assertEquals(Timing.Upstream, controller.playbackStart(startInput(1)))
        assertEquals(listOf("restore A"), port.callsNamed("restore"))
        val line = lines.first { "verify-mismatch" in it }
        assertTrue("observed=2560x1440@279961/1000" in line && "target=2560x1440@239901/1000" in line, line)
    }

    @Test
    fun `a switch that turns HDR off is a verify mismatch`() {
        port.onSwitch = { _, _, _ -> SwitchOutcome.Ok(DisplayState(MODE_240, hdr = false)) }
        assertEquals(Timing.Upstream, controller.playbackStart(startInput(1)))
        assertEquals(listOf("restore A"), port.callsNamed("restore"))
        assertTrue(logged("verify-mismatch"))
    }

    @Test
    fun `a failed or throwing restore ends idle with restore-failed`() {
        for (restore in listOf<(String) -> Boolean>({ false }, { error("jni") })) {
            val port = FakeDisplayPort().apply {
                onSwitch = { _, _, _ -> SwitchOutcome.Failed(FailureKind.SETTLE_TIMEOUT) }
                onRestore = restore
            }
            val lines = mutableListOf<String>()
            val controller = RefreshRateController(port) { lines += it }
            assertEquals(Timing.Upstream, controller.playbackStart(startInput(1)))
            assertEquals(SessionState.Idle, controller.session.state)
            assertTrue(lines.any { "restore-failed" in it })
            assertEquals(1, port.callsNamed("restore").size, "no retry")
        }
    }

    // P4-15 / requirement 7
    @Test
    fun `the next episode with the same target does not switch again`() {
        switched()
        assertEquals(Timing.DisplaySync(MODE_240.refresh), controller.playbackStart(startInput(2, fps = 24.0)))
        assertEquals(emptyList(), port.callsNamed("switch"))
        assertEquals(emptyList(), port.callsNamed("restore"))
        assertTrue(logged("same-target"))
        assertEquals(2L, assertIs<SessionState.Switched>(controller.session.state).context.owner)
    }

    @Test
    fun `the next video with another target switches straight to it`() {
        switched()
        assertEquals(Timing.DisplaySync(MODE_100.refresh), controller.playbackStart(startInput(2, fps = 25.0)))
        assertEquals(listOf("switch A 10000/100 p2"), port.callsNamed("switch"))
        assertEquals(emptyList(), port.callsNamed("restore"))
    }

    @Test
    fun `the next video with no suitable rate restores 280 (Q12)`() {
        switched()
        assertEquals(Timing.Upstream, controller.playbackStart(startInput(2, fps = 12.0)))
        assertEquals(listOf("restore A"), port.callsNamed("restore"))
        assertEquals(SessionState.Idle, controller.session.state)
    }

    // P4-14 (a)
    @Test
    fun `screen gone restores the switched display`() {
        switched()
        controller.screenGone()
        assertEquals(listOf("restore A"), port.callsNamed("restore"))
        assertEquals(SessionState.Idle, controller.session.state)
        assertEquals(MODE_280, port.states["A"]!!.mode)
    }

    @Test
    fun `screen gone while idle touches nothing`() {
        controller.screenGone()
        assertEquals(emptyList(), port.calls)
    }

    // P4-14 (b)(c) / P3-20
    @Test
    fun `app exit restores and no later start switches`() {
        switched()
        controller.appExit()
        assertEquals(listOf("restore A"), port.callsNamed("restore"))
        assertEquals(Timing.Upstream, controller.playbackStart(startInput(2)))
        assertEquals(emptyList(), port.callsNamed("switch"))
        assertTrue(logged("after-exit"))
    }

    @Test
    fun `app exit twice restores once`() {
        switched()
        controller.appExit()
        controller.appExit()
        assertEquals(1, port.callsNamed("restore").size)
    }

    // P4-16: display watcher, case H (monitor off/on drops the mode), debounced over two reads
    @Test
    fun `a lost mode seen on two ticks re-switches once, a second loss gives up`() {
        switched()
        port.states["A"] = DisplayState(MODE_280, hdr = true)
        controller.watch()
        assertEquals(emptyList(), port.callsNamed("switch"), "one read is not enough")
        controller.watch()
        assertEquals(listOf("switch A 239901/1000 p1"), port.callsNamed("switch"))
        assertTrue(logged("mode-lost"))
        assertIs<SessionState.Switched>(controller.session.state)

        port.states["A"] = DisplayState(MODE_280, hdr = true)
        controller.watch()
        controller.watch()
        assertEquals(1, port.callsNamed("switch").size, "at most one re-switch per playback")
        assertEquals(emptyList(), port.callsNamed("restore"), "nothing of ours left to restore")
        assertTrue(logged("mode-lost-again"))
        assertEquals(SessionState.Idle, controller.session.state)
    }

    @Test
    fun `a read that changes between ticks is not acted on`() {
        switched()
        port.states["A"] = DisplayState(MODE_280.copy(refresh = MODE_280.refresh.copy(numerator = 60000)), hdr = true)
        controller.watch()
        port.states["A"] = DisplayState(MODE_280, hdr = true)
        controller.watch()
        assertEquals(emptyList(), port.callsNamed("switch"))
    }

    @Test
    fun `an absent monitor is skipped by the watcher`() {
        switched()
        port.onQuery = { null }
        repeat(3) { controller.watch() }
        assertEquals(emptyList(), port.callsNamed("switch"))
        assertIs<SessionState.Switched>(controller.session.state)
    }

    @Test
    fun `an HDR toggle is reported once and never switches`() {
        switched()
        port.states["A"] = DisplayState(MODE_240, hdr = false)
        repeat(4) { controller.watch() }
        assertEquals(emptyList(), port.callsNamed("switch"))
        assertEquals(1, lines.count { "hdr-changed" in it })
        assertIs<SessionState.Switched>(controller.session.state)
    }

    // P4-22 (owner, option B): Windows resets the temporary mode when HDR is toggled; switch back in the new HDR state.
    @Test
    fun `an HDR toggle that resets the mode is switched back to 239_901 in the new HDR state`() {
        switched()
        port.states["A"] = DisplayState(qhd(279961, 1000, bpc = 8), hdr = false)
        controller.watch()
        controller.watch()
        assertEquals(listOf("switch A 239901/1000 p1"), port.callsNamed("switch"))
        assertEquals(emptyList(), port.callsNamed("restore"))
        assertTrue(logged("hdr-toggled"))
        val ctx = assertIs<SessionState.Switched>(controller.session.state).context
        assertEquals(false, ctx.original.hdr)
        assertEquals(DisplayState(qhd(239901, 1000, bpc = 8), hdr = false), port.states["A"])
    }

    @Test
    fun `the watcher does nothing while idle`() {
        controller.watch()
        assertEquals(emptyList(), port.calls)
    }

    // Q15: moved to another monitor mid-playback ⇒ restore the old one, keep playing, no new switch
    @Test
    fun `a player moved to another monitor restores the old one`() {
        switched()
        port.playerDisplays[1] = "B"
        controller.watch()
        assertEquals(listOf("restore A"), port.callsNamed("restore"))
        assertEquals(emptyList(), port.callsNamed("switch"))
        assertTrue(logged("window-moved"))
        assertEquals(SessionState.Idle, controller.session.state)
    }

    @Test
    fun `a player that is gone does not count as moved`() {
        switched()
        controller.watch()
        assertEquals(emptyList(), port.callsNamed("restore"))
        assertIs<SessionState.Switched>(controller.session.state)
    }

    @Test
    fun `a throwing port never escapes the watcher`() {
        switched()
        port.onPlayerDisplay = { error("jni") }
        port.onQuery = { error("jni") }
        controller.watch()
        assertTrue(logged("unexpected-error"))
    }

    // Phase 7 review #4: an Error (not an Exception) from a port call must not leave the session stuck
    @Test
    fun `an Error thrown by the port during a switch leaves the session usable`() {
        port.onSwitch = { _, _, _ -> throw LinkageError("native") }
        assertEquals(Timing.Upstream, controller.playbackStart(startInput(1)))
        assertEquals(SessionState.Idle, controller.session.state)
        port.onSwitch = null
        assertEquals(Timing.DisplaySync(MODE_240.refresh), controller.playbackStart(startInput(2)))
    }

    @Test
    fun `an Error thrown by the port during a restore leaves the session idle`() {
        switched()
        port.onRestore = { throw LinkageError("native") }
        controller.screenGone()
        assertEquals(SessionState.Idle, controller.session.state)
    }
}
