package com.nuvio.app.features.player.desktop.refreshrate.runtime

import com.nuvio.app.features.player.desktop.refreshrate.Timing
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Serialises every [RefreshRateController] call on one thread and runs the display watcher
 * there (SPEC P4-13, P4-16). Callers never block longer than the timeout they pass.
 */
class RefreshRateDispatcher(
    private val controller: RefreshRateController,
    private val log: (String) -> Unit,
    threadName: String = "nuvio-rr",
    watchPeriodMs: Long = 1000,
) : AutoCloseable {
    private val executor = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, threadName).apply { isDaemon = true }
    }.apply {
        scheduleWithFixedDelay({ guarded("watch") { controller.watch() } }, watchPeriodMs, watchPeriodMs, TimeUnit.MILLISECONDS)
    }

    /** Blocks at most [timeoutMs]; null on timeout (the start still completes on the thread). */
    fun playbackStart(input: StartInput, timeoutMs: Long): Timing? {
        val future = submit("start") { controller.playbackStart(input) } ?: return null
        return await(future, timeoutMs, "start p${input.playerId}")
    }

    /** Returns at once. */
    fun screenGone() {
        submit("screen-gone") { controller.screenGone() }
    }

    /** Waits at most [timeoutMs] for the restore; true if it finished in time. */
    fun appExit(timeoutMs: Long): Boolean {
        val future = submit("app-exit") { controller.appExit() } ?: return false
        return await(future, timeoutMs, "app-exit") != null
    }

    override fun close() {
        executor.shutdownNow()
    }

    private fun <T : Any> submit(name: String, body: () -> T): Future<T?>? = try {
        executor.submit(Callable { guarded(name, body) })
    } catch (e: Exception) {
        null // closed: nothing runs any more
    }

    private fun <T : Any> await(future: Future<T?>, timeoutMs: Long, name: String): T? = try {
        future.get(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (e: TimeoutException) {
        log("$name timeout after $timeoutMs ms (it finishes on the nuvio-rr thread)")
        null
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        null
    } catch (e: ExecutionException) {
        null
    }

    /** Nothing thrown by the controller or the port ever kills the thread (requirement 10). */
    private fun <T : Any> guarded(name: String, body: () -> T): T? = try {
        body()
    } catch (t: Throwable) {
        log("$name unexpected-error ${t.javaClass.simpleName}: ${t.message}")
        null
    }
}
