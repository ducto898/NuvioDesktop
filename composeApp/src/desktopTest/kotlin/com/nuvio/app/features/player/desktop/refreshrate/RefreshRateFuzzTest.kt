package com.nuvio.app.features.player.desktop.refreshrate

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** P3-5 (no public function throws) and P3-24 (random event sequences keep the invariants). Fixed seeds. */
class RefreshRateFuzzTest {
    private val iterations = 10_000

    private val specialDoubles = listOf(
        Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0, -0.0, -1.0,
        Double.MIN_VALUE, Double.MAX_VALUE, 1e-300, 23.976, 24.0, 25.0, 59.94, 1000.0, 90000.0,
    )
    private val specialLongs = listOf(0L, 1L, -1L, 1000L, 1001L, 239901L, Long.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE - 1)
    private val specialInts = listOf(0, 1, -1, 8, 10, 1440, 2560, Int.MAX_VALUE, Int.MIN_VALUE)

    private fun Random.double(): Double? = when (nextInt(4)) {
        0 -> null
        1 -> specialDoubles.random(this)
        2 -> nextDouble(0.0, 200.0)
        else -> nextDouble(-1e6, 1e6)
    }

    private fun Random.long(): Long = if (nextBoolean()) specialLongs.random(this) else nextLong()
    private fun Random.int(): Int = if (nextBoolean()) specialInts.random(this) else nextInt()
    private fun Random.rational() = Rational(long(), long())

    private fun Random.mode(): DisplayMode = when (nextInt(3)) {
        0 -> OWNER_MODES.random(this)
        1 -> DisplayMode(2560, 1440, rational(), if (nextBoolean()) 10 else 8, nextBoolean())
        else -> DisplayMode(int(), int(), rational(), int(), nextBoolean())
    }

    private fun Random.modes(): List<DisplayMode> = List(nextInt(0, 12)) { mode() }
    private fun Random.state() = DisplayState(mode(), nextBoolean())
    private fun Random.snapped() = FpsResult.Snapped(rational(), FpsSource.entries.random(this), double() ?: 0.0, double() ?: 0.0)

    private fun Random.selection(): Selection = when (nextInt(4)) {
        0 -> Selection.Switch(mode(), long(), double() ?: 0.0, snapped(), int())
        1 -> Selection.AlreadyAtTarget(mode(), long(), double() ?: 0.0, snapped(), int())
        2 -> Selection.NoSwitch(listOf("fps-missing", "disabled", "no-suitable-mode", "enumerate-failed").random(this), "")
        else -> ModeSelector.decide(nextBoolean(), double(), double(), nextBoolean(), mode(), modes())
    }

    private fun Random.anyEvent(): SessionEvent = when (nextInt(6)) {
        0 -> SessionEvent.PlaybackStart(long(), listOf("A", "B", "").random(this), state(), selection())
        1 -> SessionEvent.SwitchFinished(
            if (nextBoolean()) SwitchOutcome.Ok(state()) else SwitchOutcome.Failed(FailureKind.entries.random(this)),
        )
        2 -> SessionEvent.RestoreFinished(nextBoolean())
        3 -> SessionEvent.ScreenGone(long())
        4 -> SessionEvent.AppExit
        else -> SessionEvent.DisplayChanged(listOf("A", "B").random(this), state())
    }

    private fun Random.anySession(): Session {
        val ctx = SessionContext(listOf("A", "B").random(this), mode(), state(), long(), nextBoolean())
        val state = when (nextInt(4)) {
            0 -> SessionState.Idle
            1 -> SessionState.Switching(ctx)
            2 -> SessionState.Switched(ctx)
            else -> SessionState.Restoring(ctx.display)
        }
        return Session(state, List(nextInt(0, 4)) { anyEvent() })
    }

    private inline fun noThrow(label: String, i: Int, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            fail("$label threw on iteration $i: $t")
        }
    }

    // P3-5
    @Test
    fun `no public function throws for any input`() {
        val rnd = Random(20260928)
        repeat(iterations) { i ->
            val a = rnd.rational()
            val b = rnd.rational()
            noThrow("Rational", i) { a.sameAs(b); a.toDouble(); a.isValid }
            noThrow("snap", i) { FpsSnapper.snap(rnd.double()) }
            noThrow("resolve", i) { FpsSnapper.resolve(rnd.double(), rnd.double(), rnd.nextBoolean()) }
            noThrow("crossCheck", i) { FpsSnapper.crossCheck(a, rnd.double()) }
            noThrow("select", i) { ModeSelector.select(rnd.snapped(), rnd.mode(), rnd.modes()) }
            noThrow("decide", i) {
                ModeSelector.decide(rnd.nextBoolean(), rnd.double(), rnd.double(), rnd.nextBoolean(), rnd.mode(), rnd.modes())
            }
            noThrow("FailSafePolicy", i) { FailSafePolicy.decide(FailureKind.entries.random(rnd), rnd.nextBoolean()) }
            noThrow("step", i) { RefreshRateSession.step(rnd.anySession(), rnd.anyEvent()) }
        }
    }

    // P3-24: realistic sequences (known modes, two displays, three players), then app exit and every result delivered.
    private val targets = listOf(MODE_240, MODE_100)
    private val states = listOf(ORIG, AT_240, AT_100, DisplayState(MODE_240, hdr = false))
    private val switchFailures = FailureKind.entries - FailureKind.RESTORE_FAILED

    private fun Random.realisticSelection(): Selection = when (nextInt(4)) {
        0 -> SEL_240
        1 -> SEL_100
        2 -> SEL_ALREADY_240
        else -> SEL_NONE
    }

    private fun Random.realisticEvent(): SessionEvent = when (nextInt(6)) {
        0 -> SessionEvent.PlaybackStart(nextLong(1, 4), listOf("A", "B").random(this), states.random(this), realisticSelection())
        1 -> SessionEvent.SwitchFinished(switchResult())
        2 -> SessionEvent.RestoreFinished(nextBoolean())
        3 -> SessionEvent.ScreenGone(nextLong(1, 4))
        4 -> SessionEvent.AppExit
        else -> SessionEvent.DisplayChanged(listOf("A", "B").random(this), states.random(this))
    }

    private fun Random.switchResult(): SwitchOutcome =
        if (nextInt(4) > 0) SwitchOutcome.Ok(states.random(this)) else SwitchOutcome.Failed(switchFailures.random(this))

    @Test
    fun `random event sequences keep the session invariants`() {
        val rnd = Random(4242)
        repeat(iterations) { seq ->
            var session = Session()
            val ours = mutableSetOf<String>() // displays currently in a mode we set
            val events = List(rnd.nextInt(0, 30)) { rnd.realisticEvent() } + SessionEvent.AppExit

            fun apply(event: SessionEvent) {
                val before = session.state
                val step = try {
                    RefreshRateSession.step(session, event)
                } catch (t: Throwable) {
                    fail("sequence $seq threw on $event in $before: $t")
                }
                val label = "sequence $seq, $event in $before"

                // P3-17: at most one display command in flight.
                val displayCommands = step.commands
                assertTrue(displayCommands.size <= 1, "$label: ${step.commands}")
                val resultForInFlight = (before is SessionState.Switching && event is SessionEvent.SwitchFinished) ||
                    (before is SessionState.Restoring && event is SessionEvent.RestoreFinished)
                if (displayCommands.isNotEmpty()) {
                    assertTrue(
                        before is SessionState.Idle || before is SessionState.Switched || resultForInFlight,
                        "$label: command while one was in flight",
                    )
                }
                when (val c = displayCommands.singleOrNull()) {
                    is Command.SwitchTo -> {
                        val s = step.session.state
                        assertTrue(s is SessionState.Switching && s.context.display == c.display && s.context.target == c.mode, "$label: $s")
                    }
                    is Command.Restore -> assertEquals(SessionState.Restoring(c.display), step.session.state, label)
                    null -> assertTrue(
                        step.session.state !is SessionState.Switching && step.session.state !is SessionState.Restoring ||
                            step.session.state == before,
                        "$label: entered ${step.session.state} without a command",
                    )
                }

                // Timing is display-sync only when switched or already at target.
                if (step.timing is Timing.DisplaySync) {
                    assertTrue(
                        step.session.state is SessionState.Switched || "already-at-target" in step.reasons,
                        "$label: display-sync in ${step.session.state}, ${step.reasons}",
                    )
                }

                // Model of what the displays show.
                if (event is SessionEvent.DisplayChanged && before is SessionState.Switched &&
                    before.context.display == event.display && !event.observed.mode.refresh.sameAs(before.context.target.refresh)
                ) {
                    ours -= event.display // Windows dropped our mode (case H)
                }
                when (val c = displayCommands.singleOrNull()) {
                    is Command.SwitchTo -> ours += c.display
                    is Command.Restore -> ours -= c.display
                    null -> Unit
                }
                session = step.session
            }

            events.forEach(::apply)
            // Deliver every in-flight result.
            var guard = 0
            while (guard++ < 100) {
                when (session.state) {
                    is SessionState.Switching -> apply(SessionEvent.SwitchFinished(rnd.switchResult()))
                    is SessionState.Restoring -> apply(SessionEvent.RestoreFinished(rnd.nextBoolean()))
                    else -> break
                }
            }
            assertEquals(Session(), session, "sequence $seq did not end idle")
            assertTrue(ours.isEmpty(), "sequence $seq left $ours in our mode")
        }
    }
}
