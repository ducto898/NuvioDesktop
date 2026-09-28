package com.nuvio.app.features.player.desktop.refreshrate.runtime

import com.nuvio.app.features.player.desktop.refreshrate.*

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

    fun state(values: LongArray?): DisplayState? = TODO("Phase 4 commit B")

    fun modes(values: LongArray?): List<DisplayMode>? = TODO("Phase 4 commit B")

    fun switchOutcome(values: LongArray?): SwitchOutcome = TODO("Phase 4 commit B")

    fun timing(timing: Timing?): LongArray = TODO("Phase 4 commit B")
}
