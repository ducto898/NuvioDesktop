package com.nuvio.app.features.player.desktop.refreshrate.runtime

import com.nuvio.app.features.player.desktop.refreshrate.MODE_240
import com.nuvio.app.features.player.desktop.refreshrate.SwitchOutcome
import com.nuvio.app.features.player.desktop.refreshrate.Timing
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RefreshRateDispatcherTest {
    private val port = FakeDisplayPort()
    private val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val log: (String) -> Unit = { lines += it }
    private var dispatcher = RefreshRateDispatcher(RefreshRateController(port, log), log, watchPeriodMs = 3_600_000)

    @AfterTest
    fun tearDown() = dispatcher.close()

    private fun elapsedMs(block: () -> Unit): Long {
        val t0 = System.nanoTime()
        block()
        return (System.nanoTime() - t0) / 1_000_000
    }

    /** A switch that blocks until [release] opens, then succeeds. */
    private fun slowSwitch(release: CountDownLatch, entered: CountDownLatch = CountDownLatch(1)) {
        port.onSwitch = { display, mode, _ ->
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
            val next = port.states[display]!!.copy(mode = mode)
            port.states[display] = next
            SwitchOutcome.Ok(next)
        }
    }

    @Test
    fun `a start returns the controller's timing`() {
        assertEquals(Timing.DisplaySync(MODE_240.refresh), dispatcher.playbackStart(startInput(1), 5_000))
    }

    // P4-9: the hook never waits longer than its budget
    @Test
    fun `a start that takes too long returns null at the timeout and still completes`() {
        val release = CountDownLatch(1)
        slowSwitch(release)
        var result: Timing? = Timing.Upstream
        val ms = elapsedMs { result = dispatcher.playbackStart(startInput(1), 200) }
        assertNull(result)
        assertTrue(ms in 150..1500, "waited $ms ms")
        assertTrue(lines.any { "timeout" in it })
        release.countDown()
        assertTrue(dispatcher.appExit(5_000), "the thread is still alive and serial")
        assertEquals(listOf("restore A"), port.callsNamed("restore"), "the late switch landed, then exit restored it")
    }

    // P4-13: callers on the EDT never wait
    @Test
    fun `screen gone returns at once during a switch and restores after it`() {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        slowSwitch(release, entered)
        Thread { dispatcher.playbackStart(startInput(1), 10_000) }.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val ms = elapsedMs { dispatcher.screenGone() }
        assertTrue(ms < 100, "screenGone blocked $ms ms")
        release.countDown()
        assertTrue(dispatcher.appExit(5_000))
        assertEquals(listOf("restore A"), port.callsNamed("restore"))
    }

    // P4-14 (b): window close waits at most its budget
    @Test
    fun `app exit waits at most its timeout for a slow restore`() {
        dispatcher.playbackStart(startInput(1), 5_000)
        val release = CountDownLatch(1)
        port.onRestore = { release.await(10, TimeUnit.SECONDS); true }
        var ok = true
        val ms = elapsedMs { ok = dispatcher.appExit(300) }
        assertFalse(ok)
        assertTrue(ms in 250..1500, "waited $ms ms")
        release.countDown()
    }

    @Test
    fun `app exit with nothing switched returns quickly`() {
        assertTrue(dispatcher.appExit(2_000))
        assertEquals(emptyList(), port.callsNamed("restore"))
    }

    // P4-13 / P3-17 for real: port calls never overlap, whatever threads call in
    @Test
    fun `port calls never overlap under concurrent callers`() {
        port.onSwitch = { display, mode, _ ->
            Thread.sleep(2)
            SwitchOutcome.Ok(port.states[display]!!.copy(mode = mode).also { port.states[display] = it })
        }
        val pool = Executors.newFixedThreadPool(8)
        repeat(200) { i ->
            pool.submit {
                when (i % 4) {
                    0 -> dispatcher.playbackStart(startInput(i.toLong(), fps = if (i % 8 == 0) 25.0 else 23.976), 5_000)
                    1 -> dispatcher.screenGone()
                    2 -> dispatcher.playbackStart(startInput(i.toLong(), fps = null), 5_000)
                    else -> dispatcher.playbackStart(startInput(i.toLong()), 5_000)
                }
            }
        }
        pool.shutdown()
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS))
        assertTrue(dispatcher.appExit(5_000))
        assertEquals(1, port.maxConcurrent.get())
        assertEquals(port.registry, port.states["A"], "exit leaves the desktop mode")
    }

    // P4-16: the watcher runs on the same thread, only while switched
    @Test
    fun `the watcher polls the display while switched`() {
        dispatcher.close()
        dispatcher = RefreshRateDispatcher(RefreshRateController(port, log), log, watchPeriodMs = 20)
        Thread.sleep(150)
        assertEquals(emptyList(), port.callsNamed("query"), "idle: no polling")
        dispatcher.playbackStart(startInput(1), 5_000)
        val before = port.callsNamed("query").size
        Thread.sleep(300)
        assertTrue(port.callsNamed("query").size > before + 3, "switched: polling")
        assertEquals(1, port.maxConcurrent.get())
    }

    @Test
    fun `a throwing controller call does not kill the thread`() {
        port.onQuery = { throw OutOfMemoryError("simulated") }
        assertTrue(runCatching { dispatcher.playbackStart(startInput(1), 2_000) }.isSuccess, "never throws to the caller")
        port.onQuery = null
        assertEquals(Timing.DisplaySync(MODE_240.refresh), dispatcher.playbackStart(startInput(2), 5_000))
    }

    @Test
    fun `calls after close do nothing and do not block`() {
        dispatcher.close()
        assertNull(dispatcher.playbackStart(startInput(1), 5_000))
        dispatcher.screenGone()
        assertTrue(elapsedMs { dispatcher.appExit(5_000) } < 500)
        assertEquals(emptyList(), port.calls)
    }

    private fun awaitCalls(prefix: String): List<String> {
        val until = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < until && port.callsNamed(prefix).isEmpty()) Thread.sleep(10)
        return port.callsNamed(prefix)
    }

    // Phase 7 review #3: the hook gave up (timeout) but the switch landed later: that player still gets display sync
    @Test
    fun `a late successful start routes display-sync to its player`() {
        val release = CountDownLatch(1)
        slowSwitch(release)
        assertNull(dispatcher.playbackStart(startInput(1), 200))
        release.countDown()
        assertEquals(listOf("setTiming p1 display-sync(239901/1000)"), awaitCalls("setTiming"))
        assertTrue(dispatcher.appExit(5_000))
    }

    @Test
    fun `a late failed start needs no timing call`() {
        val release = CountDownLatch(1)
        port.onSwitch = { _, _, _ ->
            release.await(10, TimeUnit.SECONDS)
            SwitchOutcome.Failed(com.nuvio.app.features.player.desktop.refreshrate.FailureKind.SETTLE_TIMEOUT)
        }
        assertNull(dispatcher.playbackStart(startInput(1), 200))
        release.countDown()
        assertTrue(dispatcher.appExit(5_000), "serial: the late start has finished")
        assertEquals(emptyList(), port.callsNamed("setTiming"))
    }

    // Review round 2: with the real 1 s watcher, a tick runs between the timed-out start and the late-start task
    @Test
    fun `a late successful start routes display-sync even with the watcher running`() {
        dispatcher.close()
        dispatcher = RefreshRateDispatcher(RefreshRateController(port, log), log, watchPeriodMs = 20)
        val release = CountDownLatch(1)
        slowSwitch(release)
        assertNull(dispatcher.playbackStart(startInput(1), 200))
        Thread.sleep(100) // several watcher ticks queue up behind the blocked start
        release.countDown()
        assertEquals(listOf("setTiming p1 display-sync(239901/1000)"), awaitCalls("setTiming"))
        assertTrue(dispatcher.appExit(5_000))
    }
}
