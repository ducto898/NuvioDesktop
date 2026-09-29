package com.nuvio.app.features.player.desktop.refreshrate.runtime

import com.nuvio.app.features.player.desktop.refreshrate.Command
import com.nuvio.app.features.player.desktop.refreshrate.DisplayMode
import com.nuvio.app.features.player.desktop.refreshrate.DisplayState
import com.nuvio.app.features.player.desktop.refreshrate.FailureKind
import com.nuvio.app.features.player.desktop.refreshrate.HealthVerdict
import com.nuvio.app.features.player.desktop.refreshrate.ModeSelector
import com.nuvio.app.features.player.desktop.refreshrate.Rational
import com.nuvio.app.features.player.desktop.refreshrate.RefreshRateSession
import com.nuvio.app.features.player.desktop.refreshrate.ResampleHealth
import com.nuvio.app.features.player.desktop.refreshrate.Selection
import com.nuvio.app.features.player.desktop.refreshrate.Session
import com.nuvio.app.features.player.desktop.refreshrate.SessionEvent
import com.nuvio.app.features.player.desktop.refreshrate.SessionState
import com.nuvio.app.features.player.desktop.refreshrate.Step
import com.nuvio.app.features.player.desktop.refreshrate.SwitchOutcome
import com.nuvio.app.features.player.desktop.refreshrate.Timing
import com.nuvio.app.features.player.desktop.refreshrate.TimingSample
import com.nuvio.app.features.player.desktop.refreshrate.describe
import java.util.Locale

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

    /**
     * The player we last gave display-synced timing, while its health is watched (P5-11). [health] restarts
     * whenever [display] does not read the target unchanged: a display change makes mpv's rate estimate dip,
     * and that is the watcher's case, not the health check's.
     */
    private class HealthWatch(val playerId: Long, val display: String, val rate: Rational) {
        var health = ResampleHealth(rate, 0.0)
        var lastState: DisplayState? = null
    }

    private var health: HealthWatch? = null

    /** The last start's player and timing, for a start the hook stopped waiting for (review #3). */
    private var lastStart: Pair<Long, Timing>? = null

    /** One playback start: decide, switch, verify. Returns the timing for that player. */
    fun playbackStart(input: StartInput): Timing {
        if (exited) {
            log("start p${input.playerId} ignored reason=after-exit")
            return Timing.Upstream
        }
        val (current, selection) = select(input)
        log(startLine(input, current, selection))
        val event = SessionEvent.PlaybackStart(input.playerId, input.display, current ?: UNKNOWN_STATE, selection)
        val timing = apply(event) ?: Timing.Upstream
        // The hook applies it (P5-5); the newest player is the one whose health is watched.
        watchHealth(input.playerId, input.display, timing)
        lastStart = input.playerId to timing
        return timing
    }

    /**
     * The hook stopped waiting for [playerId]'s start (timeout) and continued with upstream timing. If the start
     * then ended with display-synced timing, that player still gets it, unless the session has moved on since
     * (screen gone, app exit, a newer start: they all replace or clear the health watch).
     */
    fun startTimedOut(playerId: Long) {
        val (id, timing) = lastStart ?: return
        // Not the health watch: a watcher tick queued behind the slow start has already dropped it (review round 2).
        val ctx = (session.state as? SessionState.Switched)?.context
        if (id != playerId || timing !is Timing.DisplaySync || ctx?.owner != playerId) {
            log("start p$playerId late: nothing to apply")
            return
        }
        log("start p$playerId late: applying ${timing.text()}")
        route(playerId, timing, ctx.display)
    }

    /**
     * The player screen went away (H6), or the player moved to another monitor. Only a moved player keeps
     * playing, so only then does its timing change (P5-7).
     */
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
        val timing = apply(SessionEvent.ScreenGone(owner))
        if (reason == "screen-gone") {
            if (health?.playerId == owner) health = null
            // Review #5: the owner may still be playing on another surface; a gone player just answers false.
            route(owner, Timing.Upstream, display = null)
        } else if (timing != null) {
            route(owner, timing, display = null)
        }
    }

    /** Window close / JVM shutdown (H8): restore, and never switch again. */
    fun appExit() {
        if (exited) return
        exited = true
        health = null // the restore makes mpv's rate estimate fall; never judge the closing player
        log("app-exit")
        apply(SessionEvent.AppExit)
    }

    /** One display-watcher tick (P4-16, Q15). A changed read acts only when the next tick reads the same. */
    fun watch() {
        checkHealth()
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
                    apply(SessionEvent.DisplayChanged(ctx.display, observed))?.let { route(ctx.owner, it, ctx.display) }
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
            frameCap = input.frameCap,
        )
        return current to selection
    }

    /** A timing change outside the hook goes to the session owner's mpv (P5-6, P5-7). */
    private fun route(playerId: Long, timing: Timing, display: String?) {
        val ok = try {
            port.setTiming(playerId, timing)
        } catch (e: Exception) {
            log("setTiming p$playerId ${timing.text()} failed ${FailureKind.UNEXPECTED_ERROR.code} ${e.describeError()}")
            if (health?.playerId == playerId) health = null
            return
        }
        log("setTiming p$playerId ${timing.text()} ${if (ok) "ok" else "failed"}")
        if (ok && display != null) watchHealth(playerId, display, timing) else if (health?.playerId == playerId) health = null
    }

    private fun watchHealth(playerId: Long, display: String, timing: Timing) {
        health = (timing as? Timing.DisplaySync)?.let { HealthWatch(playerId, display, it.rate) }
    }

    /** Q28: every off-rate stretch that recovered by itself, so Phase 7 soaks show how long a stall bends the estimate. */
    private fun newHealth(watch: HealthWatch) = ResampleHealth(watch.rate, clock()) { streak ->
        val error = (streak.worstFps / watch.rate.toDouble() - 1.0) * 100.0
        log(
            "rate-off p${watch.playerId} ${streak.samples} samples, worst ${"%.3f".format(Locale.ROOT, streak.worstFps)} " +
                "(${"%+.2f".format(Locale.ROOT, error)} %), recovered",
        )
    }

    /** One health tick (P5-11, Q21): clearly broken display-resample ⇒ upstream timing, the mode stays. */
    private fun checkHealth() {
        val watch = health ?: return
        val stats = try {
            port.timingStats(watch.playerId)
        } catch (e: Exception) {
            log("health p${watch.playerId} ${FailureKind.UNEXPECTED_ERROR.code} ${e.describeError()}")
            return
        }
        if (stats == null || !stats.displaySyncApplied) {
            log("health p${watch.playerId} stopped: ${if (stats == null) "player gone" else "display-sync not applied"}")
            health = null
            return
        }
        val state = try {
            port.query(watch.display)
        } catch (e: Exception) {
            null
        }
        val atTarget = state != null && relativeError(state.mode.refresh, watch.rate) <= RefreshRateSession.VERIFY_TOLERANCE
        if (!atTarget || state != watch.lastState) {
            watch.lastState = state
            watch.health = newHealth(watch) // judge only a window on a steady display at the target
            return
        }
        val sample = TimingSample(clock(), stats.drops, stats.mistimed, stats.estimatedDisplayFps, stats.timePos, stats.paused)
        val verdict = watch.health.add(sample)
        if (verdict is HealthVerdict.Unhealthy) {
            log("resample-unhealthy p${watch.playerId} ${verdict.detail}")
            health = null
            route(watch.playerId, Timing.Upstream, display = null)
        }
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
            } catch (e: Throwable) { // review #4: an Error must not leave the session in Switching
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
            } catch (e: Throwable) { // review #4: an Error must not leave the session in Restoring
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
        fun relativeError(a: Rational, b: Rational): Double = kotlin.math.abs(a.toDouble() / b.toDouble() - 1.0)

        /** Stands in for the display state when it could not be read (the selection is then NoSwitch). */
        val UNKNOWN_STATE = DisplayState(DisplayMode(0, 0, Rational(0, 1), 0), hdr = false)

        fun Double?.text() = this?.toString() ?: "na"

        fun DisplayState?.text() = if (this == null) "na" else "${mode.describe()} hdr=$hdr"

        fun Throwable.describeError() = "${javaClass.simpleName}: $message"

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
