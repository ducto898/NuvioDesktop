package com.nuvio.app.features.player.desktop.refreshrate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SessionBehaviourTest {
    private val switched = sessionIn(SessionState.Switched(CTX))
    private val switching = sessionIn(SessionState.Switching(CTX))

    // P3-17
    @Test
    fun `a start during a switch is handled after the result`() {
        val steps = run(Session(), start(1, SEL_240), start(2, SEL_ALREADY_240, current = AT_240), switchOk())
        assertEquals(listOf(Command.SwitchTo("A", MODE_240)), steps[0].commands)
        assertEquals(emptyList(), steps[1].commands, "deferred")
        val last = steps[2]
        assertEquals(emptyList(), last.commands, "same target: nothing to do")
        assertEquals(Session(SessionState.Switched(CTX.copy(owner = 2))), last.session)
        assertEquals(Timing.DisplaySync(MODE_240.refresh), last.timing)
    }

    // P3-17
    @Test
    fun `a deferred start with a new target switches once the first switch lands`() {
        val steps = run(Session(), start(1, SEL_240), start(2, SEL_100), switchOk())
        val last = steps[2]
        assertEquals(listOf(Command.SwitchTo("A", MODE_100)), last.commands)
        assertEquals(Timing.Upstream, last.timing)
        val ctx = assertIs<SessionState.Switching>(last.session.state).context
        assertEquals(ORIG, ctx.original, "the original is the desktop mode, not the first target")
        assertEquals(2L, ctx.owner)
    }

    // P3-17
    @Test
    fun `a deferred start after a failed switch runs after the restore`() {
        val steps = run(
            Session(), start(1, SEL_240), start(2, SEL_240),
            switchFailed(FailureKind.SETTLE_TIMEOUT), SessionEvent.RestoreFinished(true),
        )
        assertEquals(listOf(Command.Restore("A")), steps[2].commands)
        assertEquals(listOf(Command.SwitchTo("A", MODE_240)), steps[3].commands)
        assertEquals(2L, assertIs<SessionState.Switching>(steps[3].session.state).context.owner)
    }

    // P3-18
    @Test
    fun `same target on the next episode needs no switch`() {
        for (sel in listOf(SEL_ALREADY_240, SEL_240)) {
            val step = RefreshRateSession.step(switched, start(2, sel, current = AT_240))
            assertEquals(emptyList(), step.commands, "$sel")
            assertEquals(Session(SessionState.Switched(CTX.copy(owner = 2))), step.session)
            assertEquals(Timing.DisplaySync(MODE_240.refresh), step.timing)
            assertTrue("same-target" in step.reasons, "${step.reasons}")
        }
    }

    // P3-18
    @Test
    fun `a different target on the same display switches straight to it`() {
        val step = RefreshRateSession.step(switched, start(2, SEL_100, current = AT_240))
        assertEquals(listOf(Command.SwitchTo("A", MODE_100)), step.commands)
        assertEquals(SessionContext("A", MODE_100, ORIG, owner = 2), assertIs<SessionState.Switching>(step.session.state).context)
    }

    // P3-18
    @Test
    fun `a start on another display restores the old one first`() {
        val steps = run(switched, start(2, SEL_240, display = "B"), SessionEvent.RestoreFinished(true))
        assertEquals(listOf(Command.Restore("A")), steps[0].commands)
        assertEquals(Timing.Upstream, steps[0].timing)
        assertEquals(listOf(Command.SwitchTo("B", MODE_240)), steps[1].commands)
        assertEquals(SessionContext("B", MODE_240, ORIG, owner = 2), assertIs<SessionState.Switching>(steps[1].session.state).context)
    }

    // P3-19 (Q12: restore 280)
    @Test
    fun `a next video without a target restores the desktop mode`() {
        val step = RefreshRateSession.step(switched, start(2, SEL_NONE, current = AT_240))
        assertEquals(listOf(Command.Restore("A")), step.commands)
        assertEquals(Timing.Upstream, step.timing)
        assertTrue("fps-missing" in step.reasons, "${step.reasons}")
    }

    // P3-19
    @Test
    fun `already at target while idle uses display sync without a switch`() {
        val step = RefreshRateSession.step(Session(), start(1, SEL_ALREADY_240, current = AT_240))
        assertEquals(Session(), step.session)
        assertEquals(emptyList(), step.commands)
        assertEquals(Timing.DisplaySync(MODE_240.refresh), step.timing)
        assertTrue("already-at-target" in step.reasons)
    }

    // P3-19
    @Test
    fun `no target while idle changes nothing`() {
        val step = RefreshRateSession.step(Session(), start(1, SEL_NONE))
        assertEquals(Session(), step.session)
        assertEquals(emptyList(), step.commands)
        assertEquals(Timing.Upstream, step.timing)
    }

    // P3-20
    @Test
    fun `switched records display, original and owner`() {
        val step = RefreshRateSession.step(Session(), start(7, SEL_240, display = "B", current = ORIG))
        val ctx = assertIs<SessionState.Switching>(step.session.state).context
        assertEquals(SessionContext("B", MODE_240, ORIG, owner = 7), ctx)
        val done = RefreshRateSession.step(step.session, switchOk())
        assertEquals(SessionState.Switched(ctx), done.session.state)
    }

    // P3-20
    @Test
    fun `only the owner's screen closing restores`() {
        val other = RefreshRateSession.step(switched, SessionEvent.ScreenGone(9))
        assertEquals(switched, other.session)
        assertEquals(emptyList(), other.commands)
        assertTrue("not-owner" in other.reasons)

        val owner = RefreshRateSession.step(switched, SessionEvent.ScreenGone(1))
        assertEquals(listOf(Command.Restore("A")), owner.commands)
        assertEquals(SessionState.Restoring("A"), owner.session.state)
    }

    // P3-20
    @Test
    fun `app exit during a switch restores after the result`() {
        val steps = run(switching, SessionEvent.AppExit, switchOk(), SessionEvent.RestoreFinished(true))
        assertEquals(emptyList(), steps[0].commands)
        assertEquals(listOf(Command.Restore("A")), steps[1].commands)
        assertEquals(Session(), steps[2].session)
    }

    // P3-20
    @Test
    fun `restore finished always ends idle`() {
        val restoring = sessionIn(SessionState.Restoring("A"))
        assertEquals(Session(), RefreshRateSession.step(restoring, SessionEvent.RestoreFinished(true)).session)
        val failed = RefreshRateSession.step(restoring, SessionEvent.RestoreFinished(false))
        assertEquals(Session(), failed.session)
        assertEquals(emptyList(), failed.commands, "no retry")
        assertTrue("restore-failed" in failed.reasons)
    }

    // P3-21
    @Test
    fun `a switch is accepted only if rate, HDR and bit depth are as expected`() {
        val exactOtherDenominator = DisplayState(qhd(239901000, 1000000), hdr = true)
        assertIs<SessionState.Switched>(RefreshRateSession.step(switching, switchOk(exactOtherDenominator)).session.state)

        // Within 100 ppm but not exactly equal is accepted (Phase 7 F1: DXGI rounds some rates, e.g. 120 Hz).
        val nearlyExact = DisplayState(qhd(23990105, 100000), hdr = true)
        assertIs<SessionState.Switched>(RefreshRateSession.step(switching, switchOk(nearlyExact)).session.state)
        val fourPpm = DisplayState(qhd(239902, 1000), hdr = true)
        assertIs<SessionState.Switched>(RefreshRateSession.step(switching, switchOk(fourPpm)).session.state)

        val bad = listOf(
            DisplayState(MODE_280, hdr = true),
            DisplayState(qhd(240, 1), hdr = true), // 412 ppm: a different mode
            DisplayState(MODE_240, hdr = false),
            DisplayState(qhd(239901, 1000, bpc = 8), hdr = true),
        )
        for (observed in bad) {
            val step = RefreshRateSession.step(switching, switchOk(observed))
            assertEquals(SessionState.Restoring("A"), step.session.state, "$observed")
            assertEquals(listOf(Command.Restore("A")), step.commands)
            assertEquals(Timing.Upstream, step.timing)
            assertTrue("verify-mismatch" in step.reasons)
        }
    }

    // P3-22 (Q13: re-switch once per playback)
    @Test
    fun `a dropped mode is re-switched once, then left alone`() {
        val lost = SessionEvent.DisplayChanged("A", ORIG)
        val steps = run(switched, lost, switchOk(), lost)
        assertEquals(listOf(Command.SwitchTo("A", MODE_240)), steps[0].commands)
        assertEquals(Timing.Upstream, steps[0].timing)
        assertTrue("mode-lost" in steps[0].reasons)
        assertEquals(SessionState.Switched(CTX.copy(reswitchUsed = true)), steps[1].session.state)
        assertEquals(Timing.DisplaySync(MODE_240.refresh), steps[1].timing)
        assertEquals(Session(), steps[2].session)
        assertEquals(emptyList(), steps[2].commands)
        assertEquals(Timing.Upstream, steps[2].timing)
        assertTrue("mode-lost-again" in steps[2].reasons)
    }

    // P3-22
    @Test
    fun `a new playback start resets the re-switch allowance`() {
        val used = sessionIn(SessionState.Switched(CTX.copy(reswitchUsed = true)))
        val step = RefreshRateSession.step(used, start(2, SEL_ALREADY_240, current = AT_240))
        assertEquals(SessionState.Switched(CTX.copy(owner = 2, reswitchUsed = false)), step.session.state)
    }

    // P3-22
    @Test
    fun `a failed re-switch follows the fail-safe path`() {
        val steps = run(switched, SessionEvent.DisplayChanged("A", ORIG), switchFailed(FailureKind.SWITCH_API_ERROR))
        assertEquals(listOf(Command.Restore("A")), steps[1].commands)
        assertEquals(Timing.Upstream, steps[1].timing)
    }

    // P3-22
    @Test
    fun `same-rate display changes do not switch`() {
        val same = RefreshRateSession.step(switched, SessionEvent.DisplayChanged("A", AT_240))
        assertEquals(switched, same.session)
        assertEquals(emptyList(), same.commands)
        assertEquals(null, same.timing)

        val hdrOff = RefreshRateSession.step(switched, SessionEvent.DisplayChanged("A", DisplayState(MODE_240, hdr = false)))
        // P4-22: the recorded original follows the user's HDR choice (was: session unchanged).
        assertEquals(sessionIn(SessionState.Switched(CTX.copy(original = ORIG.copy(hdr = false)))), hdrOff.session)
        assertEquals(emptyList(), hdrOff.commands)
        assertTrue("hdr-changed" in hdrOff.reasons)

        val otherDisplay = RefreshRateSession.step(switched, SessionEvent.DisplayChanged("B", ORIG))
        assertEquals(switched, otherDisplay.session)
        assertEquals(emptyList(), otherDisplay.commands)
    }

    // P4-22 (owner, option B): Windows resets the temporary mode when HDR is toggled.
    private val sdr280 = DisplayState(qhd(279961, 1000, bpc = 8), hdr = false)
    private val sdr240 = DisplayState(qhd(239901, 1000, bpc = 8), hdr = false)
    private val sdrOriginal = CTX.copy(original = sdr280.copy(mode = ORIG.mode.copy(bitsPerColor = 8)))

    // P4-22
    @Test
    fun `an HDR toggle that drops the mode re-switches against the new HDR state`() {
        val steps = run(switched, SessionEvent.DisplayChanged("A", sdr280), switchOk(sdr240))
        assertEquals(listOf(Command.SwitchTo("A", MODE_240)), steps[0].commands)
        assertTrue("hdr-toggled" in steps[0].reasons)
        val ctx = assertIs<SessionState.Switching>(steps[0].session.state).context
        assertEquals(false, ctx.original.hdr)
        assertEquals(8, ctx.original.mode.bitsPerColor)
        assertEquals(ORIG.mode.refresh, ctx.original.mode.refresh, "the rate to go back to is still the desktop's")
        assertFalse(ctx.reswitchUsed, "an HDR toggle does not use the mode-lost re-switch")
        assertIs<SessionState.Switched>(steps[1].session.state)
        assertEquals(Timing.DisplaySync(MODE_240.refresh), steps[1].timing)
        assertTrue("switched" in steps[1].reasons)
    }

    // P4-22
    @Test
    fun `HDR off then on re-switches both times and keeps the mode-lost allowance`() {
        val steps = run(
            switched,
            SessionEvent.DisplayChanged("A", sdr280), switchOk(sdr240),
            SessionEvent.DisplayChanged("A", ORIG), switchOk(AT_240),
            SessionEvent.DisplayChanged("A", ORIG), // now a monitor off/on: HDR as recorded
        )
        assertTrue("hdr-toggled" in steps[2].reasons)
        assertEquals(true, assertIs<SessionState.Switched>(steps[3].session.state).context.original.hdr)
        assertTrue("mode-lost" in steps[4].reasons)
        assertEquals(listOf(Command.SwitchTo("A", MODE_240)), steps[4].commands)
    }

    // P4-22
    @Test
    fun `HDR toggle re-switches are capped at three per playback`() {
        val events = mutableListOf<SessionEvent>()
        repeat(2) {
            events += SessionEvent.DisplayChanged("A", sdr280); events += switchOk(sdr240)
            events += SessionEvent.DisplayChanged("A", ORIG); events += switchOk(AT_240)
        }
        val steps = run(switched, *events.toTypedArray())
        val toggled = steps.count { "hdr-toggled" in it.reasons }
        assertEquals(3, toggled)
        assertTrue("mode-lost" in steps[6].reasons, "the 4th loss falls back to P3-22: ${steps[6].reasons}")
    }

    // P4-22
    @Test
    fun `a new playback start resets the HDR toggle allowance`() {
        val used = sessionIn(SessionState.Switched(sdrOriginal.copy(hdrReswitches = 3)))
        val step = RefreshRateSession.step(used, start(2, SEL_ALREADY_240, current = sdr240))
        assertEquals(0, assertIs<SessionState.Switched>(step.session.state).context.hdrReswitches)
    }

    // Verifier round 1, problem 5: nothing switches after app exit.
    @Test
    fun `starts queued behind app exit are dropped`() {
        val steps = run(switching, SessionEvent.AppExit, start(2, SEL_100), switchOk(), SessionEvent.RestoreFinished(true))
        assertEquals(listOf(Command.Restore("A")), steps[2].commands)
        assertEquals(Session(), steps[3].session)
        assertEquals(emptyList(), steps[3].commands, "no switch after exit")
    }

    // Verifier round 1, problem 4: retargeting is named as such.
    @Test
    fun `already at another fitting mode while switched retargets`() {
        val step = RefreshRateSession.step(switched, start(2, Selection.AlreadyAtTarget(MODE_120, 5, 0.0, SNAP_23, 6), current = DisplayState(MODE_120, true)))
        assertEquals(emptyList(), step.commands)
        assertEquals(SessionState.Switched(CTX.copy(target = MODE_120, owner = 2)), step.session.state)
        assertTrue("retarget" in step.reasons, "${step.reasons}")
    }

    // Phase 7 F1: DXGI lists 120 Hz as 12000/100; after the switch Windows runs 119998/1000 (16.7 ppm)
    @Test
    fun `a switch observed within 100 ppm of a rounded DXGI rate is accepted with the observed rate`() {
        val ctx120 = CTX.copy(target = qhd(12000, 100))
        val step = RefreshRateSession.step(sessionIn(SessionState.Switching(ctx120)), switchOk(DisplayState(MODE_120, hdr = true)))
        assertIs<SessionState.Switched>(step.session.state)
        assertEquals(Timing.DisplaySync(MODE_120.refresh), step.timing)
    }

    @Test
    fun `a switched display reading the rounded twin of its target is not a lost mode`() {
        val ctx120 = CTX.copy(target = qhd(12000, 100))
        val step = RefreshRateSession.step(
            sessionIn(SessionState.Switched(ctx120)),
            SessionEvent.DisplayChanged("A", DisplayState(MODE_120, hdr = true)),
        )
        assertEquals(emptyList(), step.commands)
        assertIs<SessionState.Switched>(step.session.state)
    }

    @Test
    fun `a next start whose target is the rounded twin keeps the session`() {
        val ctx120 = CTX.copy(target = MODE_120)
        val snap60 = FpsResult.Snapped(Rational(60, 1), FpsSource.CONTAINER, 60.0, 0.0)
        val step = RefreshRateSession.step(
            sessionIn(SessionState.Switched(ctx120)),
            start(2, Selection.Switch(qhd(12000, 100), 2, 0.0, snap60, 6), current = DisplayState(MODE_120, hdr = true)),
        )
        assertEquals(emptyList(), step.commands)
        assertTrue("same-target" in step.reasons, "${step.reasons}")
        assertEquals(Timing.DisplaySync(MODE_120.refresh), step.timing)
    }
}
