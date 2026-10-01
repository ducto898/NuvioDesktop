package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// nuvio-rr fork, Phase 9 E7
class ScrubPreviewThrottleTest {
    @Test
    fun `first position at once, then at most one per interval, repeats skipped`() {
        val throttle = ScrubPreviewThrottle(intervalMs = 150)
        assertEquals(10_000L, throttle.offer(10_000, nowMs = 1_000))
        assertNull(throttle.offer(12_000, nowMs = 1_100), "inside the interval")
        assertEquals(14_000L, throttle.offer(14_000, nowMs = 1_150))
        assertNull(throttle.offer(14_000, nowMs = 1_400), "same position again")
        assertEquals(15_000L, throttle.offer(15_000, nowMs = 1_401))
    }

    @Test
    fun `a new drag previews its first position at once`() {
        val throttle = ScrubPreviewThrottle(intervalMs = 150)
        throttle.offer(10_000, nowMs = 1_000)
        throttle.reset()
        assertEquals(10_000L, throttle.offer(10_000, nowMs = 1_010))
    }
}
