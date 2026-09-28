package com.nuvio.app.features.player.desktop.refreshrate

import kotlin.test.Test
import kotlin.test.assertEquals
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

        val bad = listOf(
            DisplayState(MODE_280, hdr = true),
            DisplayState(qhd(239902, 1000), hdr = true),
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
        assertEquals(switched, hdrOff.session)
        assertEquals(emptyList(), hdrOff.commands)
        assertTrue("hdr-changed" in hdrOff.reasons)

        val otherDisplay = RefreshRateSession.step(switched, SessionEvent.DisplayChanged("B", ORIG))
        assertEquals(switched, otherDisplay.session)
        assertEquals(emptyList(), otherDisplay.commands)
    }
}
