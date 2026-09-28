package com.nuvio.app.features.player.desktop.refreshrate.runtime

import com.nuvio.app.features.player.desktop.refreshrate.Command
import com.nuvio.app.features.player.desktop.refreshrate.DisplayMode
import com.nuvio.app.features.player.desktop.refreshrate.DisplayState
import com.nuvio.app.features.player.desktop.refreshrate.FailureKind
import com.nuvio.app.features.player.desktop.refreshrate.ModeSelector
import com.nuvio.app.features.player.desktop.refreshrate.Rational
import com.nuvio.app.features.player.desktop.refreshrate.RefreshRateSession
import com.nuvio.app.features.player.desktop.refreshrate.Selection
import com.nuvio.app.features.player.desktop.refreshrate.Session
import com.nuvio.app.features.player.desktop.refreshrate.SessionEvent
import com.nuvio.app.features.player.desktop.refreshrate.SessionState
import com.nuvio.app.features.player.desktop.refreshrate.Step
import com.nuvio.app.features.player.desktop.refreshrate.SwitchOutcome
import com.nuvio.app.features.player.desktop.refreshrate.Timing
import com.nuvio.app.features.player.desktop.refreshrate.describe

/**
 * Runs the pure session (Phase 3) against a [DisplayPort] (SPEC P4-13). Not thread-safe:
 * [RefreshRateDispatcher] calls it from one thread only. Every command runs to its result
 * inside the call that caused it, so no display command is ever outstanding between calls.
 */
class RefreshRateController(
    private val port: DisplayPort,
    private val log: (String) -> Unit,
) {
    /** Seconds on a monotonic clock (the health check's windows, P5-11); replaced in tests. */
    internal var clock: () -> Double = { System.nanoTime() / 1e9 }

    var session: Session = Session()
        private set

    private var exited = false

    /** Last display state we saw on the switched display, and a changed read waiting for its second sighting. */
    private var lastObserved: DisplayState? = null
    private var candidate: DisplayState? = null

    /** One playback start: decide, switch, verify. Returns the timing for that player. */
    fun playbackStart(input: StartInput): Timing {
        if (exited) {
            log("start p${input.playerId} ignored reason=after-exit")
            return Timing.Upstream
        }
        val (current, selection) = select(input)
        log(startLine(input, current, selection))
        val event = SessionEvent.PlaybackStart(input.playerId, input.display, current ?: UNKNOWN_STATE, selection)
        return apply(event) ?: Timing.Upstream
    }

    /** The player screen went away (H6), or the player moved to another monitor. */
    fun screenGone(reason: String = "screen-gone") {
        val owner = when (val state = session.state) {
            is SessionState.Switched -> state.context.owner
            is SessionState.Switching -> state.context.owner
            else -> null
        }
        if (owner == null) {
            log("$reason: nothing switched")
            return
        }
        log("$reason p$owner")
        apply(SessionEvent.ScreenGone(owner))
    }

    /** Window close / JVM shutdown (H8): restore, and never switch again. */
    fun appExit() {
        if (exited) return
        exited = true
        log("app-exit")
        apply(SessionEvent.AppExit)
    }

    /** One display-watcher tick (P4-16, Q15). A changed read acts only when the next tick reads the same. */
    fun watch() {
        val ctx = (session.state as? SessionState.Switched)?.context ?: return
        try {
            val where = port.playerDisplay(ctx.owner)
            if (where != null && where != ctx.display) {
                log("watch p${ctx.owner} moved display=${ctx.display} -> $where")
                screenGone("window-moved")
                return
            }
            val observed = port.query(ctx.display)
            when {
                observed == null || observed == lastObserved -> candidate = null
                observed != candidate -> candidate = observed
                else -> {
                    candidate = null
                    lastObserved = observed
                    apply(SessionEvent.DisplayChanged(ctx.display, observed))
                }
            }
        } catch (e: Exception) {
            log("watch ${FailureKind.UNEXPECTED_ERROR.code} ${e.describeError()}")
        }
    }

    private fun select(input: StartInput): Pair<DisplayState?, Selection> {
        val current = try {
            port.query(input.display)
        } catch (e: Exception) {
            return null to Selection.NoSwitch(FailureKind.UNEXPECTED_ERROR.code, "query: ${e.describeError()}")
        } ?: return null to Selection.NoSwitch(FailureKind.DISPLAY_NOT_FOUND.code, "display=${input.display}")
        val modes = try {
            port.modes(input.display)
        } catch (e: Exception) {
            return current to Selection.NoSwitch(FailureKind.UNEXPECTED_ERROR.code, "modes: ${e.describeError()}")
        } ?: return current to Selection.NoSwitch(FailureKind.ENUMERATE_FAILED.code, "display=${input.display}")
        val selection = ModeSelector.decide(
            enabled = true,
            containerFps = input.containerFps,
            estimatedFps = input.estimatedFps,
            isImage = input.isImage,
            current = current.mode,
            modes = modes,
        )
        return current to selection
    }

    /** Steps [first], runs each command to its result and steps that too. Returns the last timing given. */
    private fun apply(first: SessionEvent): Timing? {
        var timing: Timing? = null
        val queue = ArrayDeque<SessionEvent>()
        queue.addLast(first)
        while (queue.isNotEmpty()) {
            val event = queue.removeFirst()
            val before = session.state
            val step = RefreshRateSession.step(session, event)
            session = step.session
            if (step.timing != null) timing = step.timing
            log(stepLine(event, before, step))
            for (command in step.commands) queue.addLast(execute(command))
        }
        return timing
    }

    private fun execute(command: Command): SessionEvent = when (command) {
        is Command.SwitchTo -> {
            val owner = (session.state as? SessionState.Switching)?.context?.owner ?: 0L
            val outcome = try {
                port.switchTo(command.display, command.mode, owner)
            } catch (e: Exception) {
                log("switch ${FailureKind.UNEXPECTED_ERROR.code} ${e.describeError()}")
                SwitchOutcome.Failed(FailureKind.UNEXPECTED_ERROR)
            }
            if (outcome is SwitchOutcome.Ok) {
                lastObserved = outcome.observed
                candidate = null
            }
            SessionEvent.SwitchFinished(outcome)
        }
        is Command.Restore -> {
            val ok = try {
                port.restore(command.display)
            } catch (e: Exception) {
                log("restore ${FailureKind.UNEXPECTED_ERROR.code} ${e.describeError()}")
                false
            }
            lastObserved = null
            candidate = null
            SessionEvent.RestoreFinished(ok)
        }
    }

    private fun startLine(input: StartInput, current: DisplayState?, selection: Selection): String = buildString {
        append("start p${input.playerId} display=${input.display}")
        append(" fps=${input.containerFps.text()} estimate=${input.estimatedFps.text()} image=${input.isImage}")
        append(" current=${current.text()}")
        when (selection) {
            is Selection.Switch -> append(
                " selection=switch snapped=${selection.fps.rate} source=${selection.fps.source.name.lowercase()}" +
                    " k=${selection.multiple} error_ppm=${"%.1f".format(selection.errorPpm)}" +
                    " target=${selection.target.describe()} considered=${selection.considered}",
            )
            is Selection.AlreadyAtTarget -> append(
                " selection=already-at-target snapped=${selection.fps.rate} source=${selection.fps.source.name.lowercase()}" +
                    " k=${selection.multiple} error_ppm=${"%.1f".format(selection.errorPpm)}" +
                    " target=${selection.mode.describe()} considered=${selection.considered}",
            )
            is Selection.NoSwitch -> append(" selection=no-switch reason=${selection.reason} detail=${selection.detail}")
        }
    }

    private fun stepLine(event: SessionEvent, before: SessionState, step: Step): String = buildString {
        append("step ${event.text()}")
        val target = when (before) {
            is SessionState.Switching -> before.context.target
            is SessionState.Switched -> before.context.target
            else -> null
        }
        if (target != null && (event is SessionEvent.SwitchFinished || event is SessionEvent.DisplayChanged)) {
            append(" target=${target.describe()}")
        }
        append(" state=${before.text()}->${step.session.state.text()}")
        append(" reasons=${step.reasons.joinToString(",")}")
        if (step.commands.isNotEmpty()) append(" commands=${step.commands.joinToString(",") { it.text() }}")
        if (step.timing != null) append(" timing=${step.timing.text()}")
        if (step.session.pending.isNotEmpty()) append(" pending=${step.session.pending.size}")
    }

    private companion object {
        /** Stands in for the display state when it could not be read (the selection is then NoSwitch). */
        val UNKNOWN_STATE = DisplayState(DisplayMode(0, 0, Rational(0, 1), 0), hdr = false)

        fun Double?.text() = this?.toString() ?: "na"

        fun DisplayState?.text() = if (this == null) "na" else "${mode.describe()} hdr=$hdr"

        fun Exception.describeError() = "${javaClass.simpleName}: $message"

        fun SessionState.text() = when (this) {
            SessionState.Idle -> "idle"
            is SessionState.Switching -> "switching"
            is SessionState.Switched -> "switched"
            is SessionState.Restoring -> "restoring"
        }

        fun Command.text() = when (this) {
            is Command.SwitchTo -> "switch($display,${mode.refresh})"
            is Command.Restore -> "restore($display)"
        }

        fun Timing.text() = when (this) {
            is Timing.DisplaySync -> "display-sync($rate)"
            Timing.Upstream -> "upstream"
        }

        fun SessionEvent.text() = when (this) {
            is SessionEvent.PlaybackStart -> "start(p$playerId,$display)"
            is SessionEvent.SwitchFinished -> when (val o = outcome) {
                is SwitchOutcome.Ok -> "switch-finished(ok) observed=${o.observed.text()}"
                is SwitchOutcome.Failed -> "switch-finished(${o.kind.code})"
            }
            is SessionEvent.RestoreFinished -> "restore-finished(${if (ok) "ok" else "failed"})"
            is SessionEvent.ScreenGone -> "screen-gone(p$playerId)"
            SessionEvent.AppExit -> "app-exit"
            is SessionEvent.DisplayChanged -> "display-changed($display) observed=${observed.text()}"
        }
    }
}
