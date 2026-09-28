package com.nuvio.app.features.player.desktop.refreshrate

/** A switched (or switching) session: which display, what we set, what to go back to, who owns it. */
data class SessionContext(
    val display: String,
    val target: DisplayMode,
    val original: DisplayState,
    val owner: Long,
    val reswitchUsed: Boolean = false,
    /** HDR-toggle re-switches used in this playback (SPEC P4-22). */
    val hdrReswitches: Int = 0,
)

sealed interface SessionState {
    data object Idle : SessionState
    data class Switching(val context: SessionContext) : SessionState
    data class Switched(val context: SessionContext) : SessionState
    data class Restoring(val display: String) : SessionState
}

sealed interface SwitchOutcome {
    data class Ok(val observed: DisplayState) : SwitchOutcome
    data class Failed(val kind: FailureKind) : SwitchOutcome
}

sealed interface SessionEvent {
    data class PlaybackStart(
        val playerId: Long,
        val display: String,
        val current: DisplayState,
        val selection: Selection,
    ) : SessionEvent

    data class SwitchFinished(val outcome: SwitchOutcome) : SessionEvent
    data class RestoreFinished(val ok: Boolean) : SessionEvent
    data class ScreenGone(val playerId: Long) : SessionEvent
    data object AppExit : SessionEvent
    data class DisplayChanged(val display: String, val observed: DisplayState) : SessionEvent
}

sealed interface Command {
    data class SwitchTo(val display: String, val mode: DisplayMode) : Command
    data class Restore(val display: String) : Command
}

sealed interface Timing {
    /** video-sync=display-resample with display-fps-override = [rate]. */
    data class DisplaySync(val rate: Rational) : Timing

    /** No mpv timing option changes (upstream behaviour). */
    data object Upstream : Timing
}

/** [pending]: events that arrived while a display command was in flight (SPEC P3-17). */
data class Session(
    val state: SessionState = SessionState.Idle,
    val pending: List<SessionEvent> = emptyList(),
)

/** [timing] null = leave mpv timing as it is. */
data class Step(
    val session: Session,
    val commands: List<Command>,
    val timing: Timing?,
    val reasons: List<String>,
)

/** Process-global refresh-rate session as a pure state machine (SPEC P3-16..P3-24). */
object RefreshRateSession {
    /** Largest relative difference between the requested and the observed rate after a switch. */
    const val VERIFY_TOLERANCE = 1e-6

    /** HDR-toggle re-switches allowed per playback start before the P3-22 rules apply (SPEC P4-22). */
    const val MAX_HDR_RESWITCHES = 3

    fun step(session: Session, event: SessionEvent): Step {
        val out = Out()
        var state = session.state
        var pending = session.pending
        when (val r = handle(state, event, out)) {
            Handled.Defer -> pending = pending + event
            is Handled.To -> {
                state = r.state
                if (r.requeue != null) pending = listOf(r.requeue) + pending
                if (event == SessionEvent.AppExit) pending = pending.withoutStarts()
            }
        }
        // Replay deferred events once no display command is in flight (P3-17).
        while (pending.isNotEmpty() && !state.inFlight()) {
            val next = pending.first()
            pending = pending.drop(1)
            when (val r = handle(state, next, out)) {
                Handled.Defer -> {
                    pending = listOf(next) + pending
                    break
                }
                is Handled.To -> {
                    state = r.state
                    if (r.requeue != null) pending = listOf(r.requeue) + pending
                    if (next == SessionEvent.AppExit) pending = pending.withoutStarts()
                }
            }
        }
        return Step(Session(state, pending), out.commands, out.timing, out.reasons)
    }

    private class Out {
        val commands = mutableListOf<Command>()
        val reasons = mutableListOf<String>()
        var timing: Timing? = null

        fun switchTo(display: String, mode: DisplayMode) {
            commands += Command.SwitchTo(display, mode)
            timing = Timing.Upstream // until the switch is verified
        }

        fun restore(display: String) {
            commands += Command.Restore(display)
            timing = Timing.Upstream
        }
    }

    private sealed interface Handled {
        data object Defer : Handled

        /** [requeue]: an event to handle again, first, once the new state has no command in flight. */
        data class To(val state: SessionState, val requeue: SessionEvent? = null) : Handled
    }

    /** Nothing may switch once the app is exiting. */
    private fun List<SessionEvent>.withoutStarts() = filterNot { it is SessionEvent.PlaybackStart }

    private fun SessionState.inFlight() = this is SessionState.Switching || this is SessionState.Restoring

    private fun handle(state: SessionState, event: SessionEvent, out: Out): Handled = when (state) {
        SessionState.Idle -> idle(event, out)
        is SessionState.Switching -> switching(state, event, out)
        is SessionState.Switched -> switched(state, event, out)
        is SessionState.Restoring -> restoring(state, event, out)
    }

    private fun ignored(state: SessionState, out: Out): Handled {
        out.reasons += "ignored"
        return Handled.To(state)
    }

    private fun idle(event: SessionEvent, out: Out): Handled {
        if (event !is SessionEvent.PlaybackStart) return ignored(SessionState.Idle, out)
        return when (val sel = event.selection) {
            is Selection.Switch -> if (sel.target.refresh.isValid) {
                out.reasons += "switching"
                out.switchTo(event.display, sel.target)
                Handled.To(SessionState.Switching(SessionContext(event.display, sel.target, event.current, event.playerId)))
            } else {
                noTarget("no-suitable-mode", out)
            }
            is Selection.AlreadyAtTarget -> if (sel.mode.refresh.isValid) {
                out.reasons += sel.reason
                out.timing = Timing.DisplaySync(sel.mode.refresh)
                Handled.To(SessionState.Idle)
            } else {
                noTarget("no-suitable-mode", out)
            }
            is Selection.NoSwitch -> noTarget(sel.reason, out)
        }
    }

    private fun noTarget(reason: String, out: Out): Handled {
        out.reasons += reason
        out.timing = Timing.Upstream
        return Handled.To(SessionState.Idle)
    }

    private fun switching(state: SessionState.Switching, event: SessionEvent, out: Out): Handled {
        val ctx = state.context
        return when (event) {
            is SessionEvent.PlaybackStart, is SessionEvent.ScreenGone, SessionEvent.AppExit -> Handled.Defer
            is SessionEvent.SwitchFinished -> when (val outcome = event.outcome) {
                is SwitchOutcome.Ok -> if (verified(ctx, outcome.observed)) {
                    out.reasons += "switched"
                    out.timing = Timing.DisplaySync(outcome.observed.mode.refresh)
                    Handled.To(SessionState.Switched(ctx))
                } else {
                    out.reasons += FailureKind.VERIFY_MISMATCH.code
                    out.restore(ctx.display)
                    Handled.To(SessionState.Restoring(ctx.display))
                }
                is SwitchOutcome.Failed -> {
                    val action = FailSafePolicy.decide(outcome.kind, switchAttempted = true)
                    out.reasons += action.reason
                    if (action.restore) {
                        out.restore(ctx.display)
                        Handled.To(SessionState.Restoring(ctx.display))
                    } else {
                        out.timing = Timing.Upstream
                        Handled.To(SessionState.Idle)
                    }
                }
            }
            is SessionEvent.RestoreFinished, is SessionEvent.DisplayChanged -> ignored(state, out)
        }
    }

    private fun switched(state: SessionState.Switched, event: SessionEvent, out: Out): Handled {
        val ctx = state.context
        return when (event) {
            is SessionEvent.PlaybackStart -> switchedStart(ctx, event, out)
            is SessionEvent.ScreenGone -> if (event.playerId == ctx.owner) {
                out.reasons += "screen-gone"
                out.restore(ctx.display)
                Handled.To(SessionState.Restoring(ctx.display))
            } else {
                out.reasons += "not-owner"
                Handled.To(state)
            }
            SessionEvent.AppExit -> {
                out.reasons += "app-exit"
                out.restore(ctx.display)
                Handled.To(SessionState.Restoring(ctx.display))
            }
            is SessionEvent.DisplayChanged -> {
                if (event.display != ctx.display) return ignored(state, out)
                val observed = event.observed
                val hdrToggled = observed.hdr != ctx.original.hdr ||
                    observed.mode.bitsPerColor != ctx.original.mode.bitsPerColor
                // P4-22: the recorded original follows the user's HDR choice; the rate to go back to stays.
                val now = if (!hdrToggled) ctx else ctx.copy(
                    original = ctx.original.copy(
                        hdr = observed.hdr,
                        mode = ctx.original.mode.copy(bitsPerColor = observed.mode.bitsPerColor),
                    ),
                )
                when {
                    sameRate(observed.mode.refresh, ctx.target.refresh) -> {
                        if (hdrToggled) out.reasons += "hdr-changed"
                        Handled.To(SessionState.Switched(now))
                    }
                    hdrToggled && ctx.hdrReswitches < MAX_HDR_RESWITCHES -> {
                        // Toggling Windows HDR resets the temporary mode to the registry rate (P4-22).
                        out.reasons += "hdr-toggled"
                        out.switchTo(ctx.display, ctx.target)
                        Handled.To(SessionState.Switching(now.copy(hdrReswitches = ctx.hdrReswitches + 1)))
                    }
                    !ctx.reswitchUsed -> {
                        // Monitor off/on dropped our temporary mode (kill-test case H): switch back once (Q13).
                        out.reasons += "mode-lost"
                        out.switchTo(ctx.display, ctx.target)
                        Handled.To(SessionState.Switching(now.copy(reswitchUsed = true)))
                    }
                    else -> {
                        out.reasons += "mode-lost-again"
                        out.timing = Timing.Upstream
                        Handled.To(SessionState.Idle)
                    }
                }
            }
            is SessionEvent.SwitchFinished, is SessionEvent.RestoreFinished -> ignored(state, out)
        }
    }

    private fun switchedStart(ctx: SessionContext, event: SessionEvent.PlaybackStart, out: Out): Handled {
        val restore = { reason: String ->
            out.reasons += reason
            out.restore(ctx.display)
            Handled.To(SessionState.Restoring(ctx.display))
        }
        if (event.display != ctx.display) {
            // Window moved to another monitor: restore this one, then handle the start from idle.
            out.reasons += "display-moved"
            out.restore(ctx.display)
            return Handled.To(SessionState.Restoring(ctx.display), requeue = event)
        }
        val keep = { rate: Rational, target: DisplayMode ->
            out.reasons += if (target == ctx.target) "same-target" else "retarget"
            out.timing = Timing.DisplaySync(rate)
            Handled.To(
                SessionState.Switched(
                    ctx.copy(target = target, owner = event.playerId, reswitchUsed = false, hdrReswitches = 0),
                ),
            )
        }
        return when (val sel = event.selection) {
            is Selection.Switch -> when {
                !sel.target.refresh.isValid -> restore("no-suitable-mode")
                sel.target.refresh.sameAs(ctx.target.refresh) -> keep(ctx.target.refresh, ctx.target)
                else -> {
                    out.reasons += "switching"
                    out.switchTo(ctx.display, sel.target)
                    Handled.To(
                        SessionState.Switching(
                            ctx.copy(target = sel.target, owner = event.playerId, reswitchUsed = false, hdrReswitches = 0),
                        ),
                    )
                }
            }
            is Selection.AlreadyAtTarget -> when {
                !sel.mode.refresh.isValid -> restore("no-suitable-mode")
                sel.mode.refresh.sameAs(ctx.target.refresh) -> keep(ctx.target.refresh, ctx.target)
                // The display already shows a fitting mode other than the one we recorded: keep our session on it.
                else -> keep(sel.mode.refresh, sel.mode)
            }
            is Selection.NoSwitch -> restore(sel.reason) // Q12: back to the desktop mode
        }
    }

    private fun restoring(state: SessionState.Restoring, event: SessionEvent, out: Out): Handled = when (event) {
        is SessionEvent.PlaybackStart, is SessionEvent.ScreenGone, SessionEvent.AppExit -> Handled.Defer
        is SessionEvent.RestoreFinished -> {
            out.reasons += if (event.ok) "restored" else FailureKind.RESTORE_FAILED.code
            Handled.To(SessionState.Idle)
        }
        is SessionEvent.SwitchFinished, is SessionEvent.DisplayChanged -> ignored(state, out)
    }

    private fun sameRate(a: Rational, b: Rational): Boolean =
        a.sameAs(b) || relativeError(a.toDouble(), b.toDouble()) <= VERIFY_TOLERANCE

    private fun verified(ctx: SessionContext, observed: DisplayState): Boolean =
        sameRate(observed.mode.refresh, ctx.target.refresh) &&
            observed.mode.width == ctx.target.width && observed.mode.height == ctx.target.height &&
            observed.mode.bitsPerColor == ctx.original.mode.bitsPerColor &&
            observed.hdr == ctx.original.hdr
}
