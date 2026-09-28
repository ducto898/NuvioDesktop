package com.nuvio.app.features.player.desktop.refreshrate.runtime

import com.nuvio.app.features.player.desktop.refreshrate.DisplayMode
import com.nuvio.app.features.player.desktop.refreshrate.DisplayState
import com.nuvio.app.features.player.desktop.refreshrate.SwitchOutcome
import com.nuvio.app.features.player.desktop.refreshrate.Timing

/**
 * The Win32 side, implemented in display_mode_matcher.cpp (compiled into player_bridge.dll).
 * Only used after the first native upcall, so the DLL is always loaded by then.
 */
internal object NativeDisplayPort : DisplayPort {
    @JvmStatic private external fun nativeQuery(display: String): LongArray?

    @JvmStatic private external fun nativeModes(display: String): LongArray?

    @JvmStatic private external fun nativeSwitch(
        display: String,
        width: Int,
        height: Int,
        numerator: Long,
        denominator: Long,
        playerId: Long,
    ): LongArray?

    @JvmStatic private external fun nativeRestore(display: String): Boolean

    @JvmStatic private external fun nativePlayerDisplay(playerId: Long): String?

    @JvmStatic private external fun nativeLog(line: String)

    @JvmStatic private external fun nativeSetTiming(playerId: Long, kind: Long, numerator: Long, denominator: Long): Boolean

    @JvmStatic private external fun nativeTimingStats(playerId: Long): DoubleArray?

    override fun query(display: String): DisplayState? = NativeCodec.state(nativeQuery(display))

    override fun modes(display: String): List<DisplayMode>? = NativeCodec.modes(nativeModes(display))

    override fun switchTo(display: String, mode: DisplayMode, playerId: Long): SwitchOutcome =
        NativeCodec.switchOutcome(
            nativeSwitch(display, mode.width, mode.height, mode.refresh.numerator, mode.refresh.denominator, playerId),
        )

    override fun restore(display: String): Boolean = nativeRestore(display)

    override fun playerDisplay(playerId: Long): String? = nativePlayerDisplay(playerId)

    override fun setTiming(playerId: Long, timing: Timing): Boolean {
        val t = NativeCodec.timing(timing)
        return nativeSetTiming(playerId, t[0], t[1], t[2])
    }

    override fun timingStats(playerId: Long): TimingStats? = NativeCodec.timingStats(nativeTimingStats(playerId))

    /** The native `[nuvio-rr]` file sink (refresh-rate.log). */
    fun log(line: String) {
        try {
            nativeLog(line)
        } catch (e: UnsatisfiedLinkError) {
            // DLL not loaded: nothing native to log to.
        }
    }
}
