package com.nuvio.app.features.player.desktop.refreshrate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// SPEC P5-11 (Q21): only clear breakage of display-synced timing is unhealthy.
class ResampleHealthTest {
    private val target = MODE_240.refresh
    private val rate = target.toDouble()

    /** Feeds one sample per second from t=0 to [seconds]; [at] gives each sample's values. */
    private fun feed(
        health: ResampleHealth,
        seconds: Int,
        at: (Int) -> TimingSample,
    ): List<HealthVerdict> = (0..seconds).map { health.add(at(it)) }

    private fun sample(
        t: Double,
        drops: Long = 0,
        mistimed: Long = 0,
        est: Double? = rate,
        pos: Double? = t,
        paused: Boolean = false,
    ) = TimingSample(t, drops, mistimed, est, pos, paused)

    @Test
    fun `a clean run is never unhealthy and is judged healthy after the first window`() {
        val verdicts = feed(ResampleHealth(target, 0.0), 60) { sample(it.toDouble(), est = rate * (1 + 2e-5)) }
        assertTrue(verdicts.none { it is HealthVerdict.Unhealthy }, "$verdicts")
        assertTrue(verdicts.take(15).all { it == HealthVerdict.Wait }, "5 s ignored + one 10 s window: ${verdicts.take(16)}")
        assertEquals(HealthVerdict.Healthy, verdicts[15])
        assertEquals(HealthVerdict.Healthy, verdicts.last())
    }

    @Test
    fun `the first 5 s are ignored however bad they are`() {
        val health = ResampleHealth(target, 100.0)
        val verdicts = (0..4).map { health.add(sample(100.0 + it, drops = 1000L * it, est = 6.5)) }
        assertTrue(verdicts.all { it == HealthVerdict.Wait }, "$verdicts")
    }

    @Test
    fun `more than 20 drops plus mistimed in 10 s is unhealthy, 20 is not`() {
        // 2 bad frames per second from t=5 ⇒ 20 per 10 s window
        val ok = feed(ResampleHealth(target, 0.0), 40) { sample(it.toDouble(), drops = maxOf(0, it - 5L), mistimed = maxOf(0, it - 5L)) }
        assertTrue(ok.none { it is HealthVerdict.Unhealthy }, "exactly 20 per window: $ok")
        val bad = feed(ResampleHealth(target, 0.0), 40) { sample(it.toDouble(), drops = 2L * it, mistimed = maxOf(0, it - 5L)) }
        assertIs<HealthVerdict.Unhealthy>(bad.first { it != HealthVerdict.Wait }, "30 per window")
    }

    @Test
    fun `a collapsed display rate is unhealthy (Phase 2b, 6_5 Hz under a 200 fps cap)`() {
        val verdicts = feed(ResampleHealth(target, 0.0), 20) { sample(it.toDouble(), est = 6.5) }
        // the rate error has to hold for 3 judged samples in a row (Phase 6 run p6-env1-set-off: one stall)
        assertTrue(verdicts.take(17).none { it is HealthVerdict.Unhealthy }, "$verdicts")
        val u = assertIs<HealthVerdict.Unhealthy>(verdicts[17])
        assertTrue("6.5" in u.detail, u.detail)
    }

    @Test
    fun `display rate more than 1 percent off is unhealthy, 0_8 percent is not`() {
        val off12 = feed(ResampleHealth(target, 0.0), 20) { sample(it.toDouble(), est = rate * 0.988) }
        assertIs<HealthVerdict.Unhealthy>(off12[17])
        // Phase 2b 4K HDR at Normal power: -0.795 %, 19 mistimed/min ⇒ degraded but not "clearly broken"
        val off08 = feed(ResampleHealth(target, 0.0), 40) { sample(it.toDouble(), mistimed = it / 3L, est = rate * 0.992) }
        assertTrue(off08.none { it is HealthVerdict.Unhealthy }, "$off08")
    }

    @Test
    fun `one stall that bends the rate estimate for 1 or 2 samples is not unhealthy`() {
        // Measured 2026-09-28 23:23:12: 25 s at 239.897, then one 1.3 s stall (1 drop) ⇒ estimate 235.649 (-1.8 %).
        for (badSamples in 1..2) {
            val verdicts = feed(ResampleHealth(target, 0.0), 40) {
                sample(it.toDouble(), drops = if (it >= 25) 1L else 0L, est = if (it in 25 until 25 + badSamples) 235.649 else rate)
            }
            assertTrue(verdicts.none { it is HealthVerdict.Unhealthy }, "$badSamples bad: $verdicts")
            assertEquals(HealthVerdict.Healthy, verdicts.last())
        }
    }

    @Test
    fun `a rate error that lasts 3 samples is unhealthy, and a good sample restarts the count`() {
        val lasting = feed(ResampleHealth(target, 0.0), 40) { sample(it.toDouble(), est = if (it >= 25) 235.649 else rate) }
        assertTrue(lasting.take(27).none { it is HealthVerdict.Unhealthy }, "$lasting")
        assertIs<HealthVerdict.Unhealthy>(lasting[27])
        // bad, bad, good, bad, bad, good ... never 3 in a row
        val broken = feed(ResampleHealth(target, 0.0), 60) { sample(it.toDouble(), est = if (it >= 20 && it % 3 != 0) 235.649 else rate) }
        assertTrue(broken.none { it is HealthVerdict.Unhealthy }, "$broken")
    }

    @Test
    fun `a paused window is not judged`() {
        // playback stops advancing at t=10 (pause); counters and the rate estimate go bad meanwhile
        val verdicts = feed(ResampleHealth(target, 0.0), 30) {
            val t = it.toDouble()
            if (it <= 10) sample(t) else sample(t, drops = 100L * it, est = 6.5, pos = 10.0, paused = true)
        }
        assertTrue(verdicts.drop(11).all { it == HealthVerdict.Wait }, "$verdicts")
    }

    @Test
    fun `a window with too little or too much playback advance is not judged (buffering, seek)`() {
        val buffering = feed(ResampleHealth(target, 0.0), 30) { sample(it.toDouble(), drops = 50L * it, pos = it * 0.5) }
        assertTrue(buffering.none { it is HealthVerdict.Unhealthy }, "advance 5 s per 10 s: $buffering")
        val seeking = feed(ResampleHealth(target, 0.0), 30) { sample(it.toDouble(), drops = 50L * it, pos = it * 2.0) }
        assertTrue(seeking.none { it is HealthVerdict.Unhealthy }, "advance 20 s per 10 s: $seeking")
    }

    @Test
    fun `missing values are judged on what is there`() {
        val noEstimate = feed(ResampleHealth(target, 0.0), 20) { sample(it.toDouble(), drops = 5L * it, est = null) }
        assertIs<HealthVerdict.Unhealthy>(noEstimate[15], "counters alone")
        val noPos = feed(ResampleHealth(target, 0.0), 20) { sample(it.toDouble(), drops = 5L * it, pos = null) }
        assertTrue(noPos.all { it == HealthVerdict.Wait }, "no time-pos ⇒ can't tell pause from play: $noPos")
    }

    @Test
    fun `counters that go backwards are not judged`() {
        val verdicts = feed(ResampleHealth(target, 0.0), 30) { sample(it.toDouble(), drops = if (it < 18) 1000L else 0L) }
        assertTrue(verdicts.none { it is HealthVerdict.Unhealthy }, "$verdicts")
    }
}
