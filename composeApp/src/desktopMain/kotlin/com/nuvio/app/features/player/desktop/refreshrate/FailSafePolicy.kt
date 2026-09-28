package com.nuvio.app.features.player.desktop.refreshrate

enum class FailureKind(val code: String) {
    ENUMERATE_FAILED("enumerate-failed"),
    DISPLAY_NOT_FOUND("display-not-found"),
    SWITCH_API_ERROR("switch-api-error"),
    SETTLE_TIMEOUT("settle-timeout"),
    STOP_REQUESTED("stop-requested"),
    VERIFY_MISMATCH("verify-mismatch"),
    RESTORE_FAILED("restore-failed"),
    UNEXPECTED_ERROR("unexpected-error"),
}

/** [restore] = undo our mode change; playback always continues with upstream timing. */
data class FailSafeAction(val restore: Boolean, val reason: String)

/** Requirement 10: any failure keeps playback going at the current rate (SPEC P3-23). */
object FailSafePolicy {
    fun decide(kind: FailureKind, switchAttempted: Boolean): FailSafeAction =
        // Restoring is idempotent (it re-applies the registry mode), so any failure after an attempt restores.
        // A failed restore is not retried: Windows reverts CDS_FULLSCREEN when the process exits (D7).
        FailSafeAction(restore = switchAttempted && kind != FailureKind.RESTORE_FAILED, reason = kind.code)
}
