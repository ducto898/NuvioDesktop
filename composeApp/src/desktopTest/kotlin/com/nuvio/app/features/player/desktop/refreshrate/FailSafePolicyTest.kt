package com.nuvio.app.features.player.desktop.refreshrate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** P3-23: every failure kind keeps playback going at the current rate. */
class FailSafePolicyTest {
    @Test
    fun `failures before a switch attempt change nothing`() {
        for (kind in FailureKind.entries) {
            assertEquals(FailSafeAction(restore = false, reason = kind.code), FailSafePolicy.decide(kind, switchAttempted = false), "$kind")
        }
    }

    @Test
    fun `failures after a switch attempt restore, except a failed restore`() {
        for (kind in FailureKind.entries) {
            val expected = FailSafeAction(restore = kind != FailureKind.RESTORE_FAILED, reason = kind.code)
            assertEquals(expected, FailSafePolicy.decide(kind, switchAttempted = true), "$kind")
        }
    }

    @Test
    fun `codes are stable and lowercase`() {
        assertEquals(
            listOf(
                "enumerate-failed", "display-not-found", "switch-api-error", "settle-timeout",
                "stop-requested", "verify-mismatch", "restore-failed", "unexpected-error",
            ),
            FailureKind.entries.map { it.code },
        )
    }

    @Test
    fun `a failed switch restores and plays with upstream timing`() {
        for (kind in FailureKind.entries - FailureKind.RESTORE_FAILED) {
            val step = RefreshRateSession.step(sessionIn(SessionState.Switching(CTX)), switchFailed(kind))
            assertEquals(SessionState.Restoring("A"), step.session.state, "$kind")
            assertEquals(listOf(Command.Restore("A")), step.commands, "$kind")
            assertEquals(Timing.Upstream, step.timing, "$kind")
            assertTrue(kind.code in step.reasons, "$kind: ${step.reasons}")
        }
    }

    @Test
    fun `a restore-failed outcome ends idle without a retry`() {
        val step = RefreshRateSession.step(sessionIn(SessionState.Switching(CTX)), switchFailed(FailureKind.RESTORE_FAILED))
        assertEquals(Session(), step.session)
        assertEquals(emptyList(), step.commands)
        assertEquals(Timing.Upstream, step.timing)
    }

    @Test
    fun `failures reported before a switch play on with upstream timing`() {
        for (kind in FailureKind.entries) {
            val step = RefreshRateSession.step(Session(), start(1, Selection.NoSwitch(kind.code, "test")))
            assertEquals(Session(), step.session, "$kind")
            assertEquals(emptyList(), step.commands, "$kind")
            assertEquals(Timing.Upstream, step.timing, "$kind")
            assertTrue(kind.code in step.reasons, "$kind")
        }
    }
}
