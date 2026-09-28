package com.nuvio.app.features.player.desktop.refreshrate

import kotlin.test.Test
import kotlin.test.assertEquals

/** P3-16: every (state x event) pair, one representative event per kind. */
class SessionTransitionTableTest {
    private val idle = SessionState.Idle
    private val switching = SessionState.Switching(CTX)
    private val switched = SessionState.Switched(CTX)
    private val restoring = SessionState.Restoring("A")

    // A new player (2) on display A wants 100 Hz.
    private val eStart = start(playerId = 2, selection = SEL_100)
    private val eSwitchOk = switchOk(AT_240)
    private val eRestoreOk = SessionEvent.RestoreFinished(true)
    private val eScreenGone = SessionEvent.ScreenGone(1)
    private val eExit = SessionEvent.AppExit
    private val eLost = SessionEvent.DisplayChanged("A", ORIG)

    private val to100 = SessionContext("A", MODE_100, ORIG, owner = 2)

    private data class Cell(
        val state: SessionState,
        val event: SessionEvent,
        val expected: Session,
        val commands: List<Command>,
        val timing: Timing?,
    )

    private val cells = listOf(
        Cell(idle, eStart, Session(SessionState.Switching(to100)), listOf(Command.SwitchTo("A", MODE_100)), Timing.Upstream),
        Cell(idle, eSwitchOk, Session(idle), emptyList(), null),
        Cell(idle, eRestoreOk, Session(idle), emptyList(), null),
        Cell(idle, eScreenGone, Session(idle), emptyList(), null),
        Cell(idle, eExit, Session(idle), emptyList(), null),
        Cell(idle, eLost, Session(idle), emptyList(), null),

        Cell(switching, eStart, Session(switching, listOf(eStart)), emptyList(), null),
        Cell(switching, eSwitchOk, Session(switched), emptyList(), Timing.DisplaySync(MODE_240.refresh)),
        Cell(switching, eRestoreOk, Session(switching), emptyList(), null),
        Cell(switching, eScreenGone, Session(switching, listOf(eScreenGone)), emptyList(), null),
        Cell(switching, eExit, Session(switching, listOf(eExit)), emptyList(), null),
        Cell(switching, eLost, Session(switching), emptyList(), null),

        Cell(switched, eStart, Session(SessionState.Switching(to100)), listOf(Command.SwitchTo("A", MODE_100)), Timing.Upstream),
        Cell(switched, eSwitchOk, Session(switched), emptyList(), null),
        Cell(switched, eRestoreOk, Session(switched), emptyList(), null),
        Cell(switched, eScreenGone, Session(restoring), listOf(Command.Restore("A")), Timing.Upstream),
        Cell(switched, eExit, Session(restoring), listOf(Command.Restore("A")), Timing.Upstream),
        Cell(
            switched, eLost,
            Session(SessionState.Switching(CTX.copy(reswitchUsed = true))),
            listOf(Command.SwitchTo("A", MODE_240)), Timing.Upstream,
        ),

        Cell(restoring, eStart, Session(restoring, listOf(eStart)), emptyList(), null),
        Cell(restoring, eSwitchOk, Session(restoring), emptyList(), null),
        Cell(restoring, eRestoreOk, Session(idle), emptyList(), null),
        Cell(restoring, eScreenGone, Session(restoring, listOf(eScreenGone)), emptyList(), null),
        Cell(restoring, eExit, Session(restoring, listOf(eExit)), emptyList(), null),
        Cell(restoring, eLost, Session(restoring), emptyList(), null),
    )

    @Test
    fun `all 24 state and event pairs are defined`() {
        assertEquals(24, cells.size)
        assertEquals(4, cells.map { it.state }.distinct().size)
        assertEquals(6, cells.map { it.event::class }.distinct().size)
        for (c in cells) {
            val label = "${c.state::class.simpleName} + ${c.event::class.simpleName}"
            val step = RefreshRateSession.step(Session(c.state), c.event)
            assertEquals(c.expected, step.session, "$label: session")
            assertEquals(c.commands, step.commands, "$label: commands")
            assertEquals(c.timing, step.timing, "$label: timing")
            assert(step.reasons.all { it.isNotBlank() && it == it.lowercase() }) { "$label: reasons ${step.reasons}" }
        }
    }
}
