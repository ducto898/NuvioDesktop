package com.nuvio.app.features.player.desktop.refreshrate

/** A switched (or switching) session: which display, what we set, what to go back to, who owns it. */
data class SessionContext(
    val display: String,
    val target: DisplayMode,
    val original: DisplayState,
    val owner: Long,
    val reswitchUsed: Boolean = false,
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
    fun step(session: Session, event: SessionEvent): Step = TODO()
}
