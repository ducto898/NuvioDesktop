package com.nuvio.app.features.player.desktop.refreshrate.runtime

import com.nuvio.app.features.player.desktop.refreshrate.DisplayMode
import com.nuvio.app.features.player.desktop.refreshrate.DisplayState
import com.nuvio.app.features.player.desktop.refreshrate.FailureKind
import com.nuvio.app.features.player.desktop.refreshrate.MODE_280
import com.nuvio.app.features.player.desktop.refreshrate.OWNER_MODES
import com.nuvio.app.features.player.desktop.refreshrate.SwitchOutcome
import com.nuvio.app.features.player.desktop.refreshrate.Timing
import java.util.concurrent.atomic.AtomicInteger

/**
 * A monitor that behaves like the owner's: switching changes [states], restoring goes back to [registry].
 * Every call is recorded; each operation can be replaced to inject failures, exceptions or delays.
 */
internal class FakeDisplayPort(
    val registry: DisplayState = DisplayState(MODE_280, hdr = true),
    var modeList: List<DisplayMode>? = OWNER_MODES,
) : DisplayPort {
    val calls = mutableListOf<String>()
    val states = mutableMapOf("A" to registry, "B" to registry)
    val playerDisplays = mutableMapOf<Long, String>()

    var onQuery: ((String) -> DisplayState?)? = null
    var onSwitch: ((String, DisplayMode, Long) -> SwitchOutcome)? = null
    var onRestore: ((String) -> Boolean)? = null
    var onPlayerDisplay: ((Long) -> String?)? = null
    var onSetTiming: ((Long, Timing) -> Boolean)? = null

    /** mpv counters per player for the health check; absent = player gone. */
    val stats = mutableMapOf<Long, TimingStats>()
    var onTimingStats: ((Long) -> TimingStats?)? = null

    /** Highest number of port calls running at the same time (dispatcher tests). */
    val maxConcurrent = AtomicInteger(0)
    private val running = AtomicInteger(0)

    private fun <T> track(name: String, body: () -> T): T {
        synchronized(calls) { calls += name }
        val now = running.incrementAndGet()
        maxConcurrent.accumulateAndGet(now) { a, b -> maxOf(a, b) }
        try {
            return body()
        } finally {
            running.decrementAndGet()
        }
    }

    fun callsNamed(prefix: String) = synchronized(calls) { calls.filter { it.startsWith(prefix) } }

    override fun query(display: String): DisplayState? = track("query $display") {
        onQuery?.invoke(display) ?: states[display]
    }

    override fun modes(display: String): List<DisplayMode>? = track("modes $display") {
        if (display in states) modeList else null
    }

    override fun switchTo(display: String, mode: DisplayMode, playerId: Long): SwitchOutcome =
        track("switch $display ${mode.refresh} p$playerId") {
            onSwitch?.invoke(display, mode, playerId) ?: run {
                val current = states[display] ?: return@run SwitchOutcome.Failed(FailureKind.DISPLAY_NOT_FOUND)
                // Like the real monitor: the link bpc stays what it is (8 with HDR off, 10 with HDR on).
                val next = current.copy(mode = mode.copy(bitsPerColor = current.mode.bitsPerColor))
                states[display] = next
                SwitchOutcome.Ok(next)
            }
        }

    override fun restore(display: String): Boolean = track("restore $display") {
        onRestore?.invoke(display) ?: run {
            states[display] = registry
            true
        }
    }

    override fun playerDisplay(playerId: Long): String? = track("playerDisplay p$playerId") {
        onPlayerDisplay?.invoke(playerId) ?: playerDisplays[playerId]
    }

    override fun setTiming(playerId: Long, timing: Timing): Boolean = track("setTiming p$playerId ${timing.text()}") {
        onSetTiming?.invoke(playerId, timing) ?: true
    }

    override fun timingStats(playerId: Long): TimingStats? = track("timingStats p$playerId") {
        onTimingStats?.invoke(playerId) ?: stats[playerId]
    }
}

internal fun Timing.text() = when (this) {
    is Timing.DisplaySync -> "display-sync(${rate})"
    Timing.Upstream -> "upstream"
}

internal fun startInput(
    playerId: Long,
    fps: Double? = 23.976,
    display: String = "A",
    estimate: Double? = null,
    frameCap: Double? = null,
) = StartInput(playerId, display, containerFps = fps, estimatedFps = estimate, isImage = false, frameCap = frameCap)
