package com.nuvio.app.features.player.desktop.refreshrate.runtime

import com.nuvio.app.features.player.desktop.refreshrate.DisplayMode
import com.nuvio.app.features.player.desktop.refreshrate.DisplayState
import com.nuvio.app.features.player.desktop.refreshrate.FailureKind
import com.nuvio.app.features.player.desktop.refreshrate.Rational
import com.nuvio.app.features.player.desktop.refreshrate.SwitchOutcome
import com.nuvio.app.features.player.desktop.refreshrate.Timing

/**
 * The plain `long[]` layouts the native side (display_mode_matcher.cpp) uses across JNI.
 * state = [width, height, num, den, bpc, hdr 0/1, interlaced 0/1]
 * modes = n x [width, height, num, den, bpc, interlaced 0/1]
 * switch = [code, state..., elapsedMs]; code 0 = ok, else FailureKind.ordinal + 1
 * timing (to native) = [kind, num, den]; kind 0 = none/timeout, 1 = upstream, 2 = display-sync
 */
object NativeCodec {
    const val STATE_SIZE = 7
    const val MODE_SIZE = 6

    fun state(values: LongArray?): DisplayState? {
        if (values == null || values.size != STATE_SIZE) return null
        return stateAt(values, 0)
    }

    fun modes(values: LongArray?): List<DisplayMode>? {
        if (values == null || values.size % MODE_SIZE != 0) return null
        return (0 until values.size / MODE_SIZE).map { i ->
            val o = i * MODE_SIZE
            DisplayMode(
                width = values[o].toInt(),
                height = values[o + 1].toInt(),
                refresh = Rational(values[o + 2], values[o + 3]),
                bitsPerColor = values[o + 4].toInt(),
                interlaced = values[o + 5] != 0L,
            )
        }
    }

    fun switchOutcome(values: LongArray?): SwitchOutcome {
        if (values == null || values.size != STATE_SIZE + 2) return SwitchOutcome.Failed(FailureKind.UNEXPECTED_ERROR)
        val code = values[0]
        if (code == 0L) return SwitchOutcome.Ok(stateAt(values, 1))
        val kind = FailureKind.entries.getOrNull((code - 1).toInt().takeIf { code in 1..FailureKind.entries.size.toLong() } ?: -1)
        return SwitchOutcome.Failed(kind ?: FailureKind.UNEXPECTED_ERROR)
    }

    fun timing(timing: Timing?): LongArray = when (timing) {
        null -> longArrayOf(0, 0, 0)
        Timing.Upstream -> longArrayOf(1, 0, 0)
        is Timing.DisplaySync -> longArrayOf(2, timing.rate.numerator, timing.rate.denominator)
    }

    private fun stateAt(values: LongArray, o: Int) = DisplayState(
        mode = DisplayMode(
            width = values[o].toInt(),
            height = values[o + 1].toInt(),
            refresh = Rational(values[o + 2], values[o + 3]),
            bitsPerColor = values[o + 4].toInt(),
            interlaced = values[o + 6] != 0L,
        ),
        hdr = values[o + 5] != 0L,
    )
}
