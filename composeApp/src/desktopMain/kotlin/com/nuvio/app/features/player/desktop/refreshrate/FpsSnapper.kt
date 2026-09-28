package com.nuvio.app.features.player.desktop.refreshrate

enum class FpsSource { CONTAINER, ESTIMATE }

sealed interface FpsResult {
    /** [input] is the raw fps that snapped; [errorPpm] its distance from [rate]. */
    data class Snapped(
        val rate: Rational,
        val source: FpsSource,
        val input: Double,
        val errorPpm: Double,
    ) : FpsResult

    data class Rejected(
        val reason: String,
        val containerFps: Double?,
        val estimatedFps: Double?,
    ) : FpsResult
}

enum class CrossCheck { AGREE, DISAGREE, UNAVAILABLE }

object FpsSnapper {
    /** Relative tolerance for snapping to a standard rate (just over 1000/1001). */
    const val SNAP_TOLERANCE = 0.001

    /** Relative limit between the snapped rate and mpv's estimated-vf-fps. */
    const val CROSS_CHECK_TOLERANCE = 0.005

    val STANDARD_RATES: List<Rational> = listOf(
        Rational(24000, 1001), Rational(24, 1), Rational(25, 1),
        Rational(30000, 1001), Rational(30, 1), Rational(48000, 1001),
        Rational(48, 1), Rational(50, 1), Rational(60000, 1001), Rational(60, 1),
    )

    /** Nearest standard rate within [SNAP_TOLERANCE], or null. */
    fun snap(fps: Double?): Rational? = TODO()

    /** Container fps first, the estimate as a fallback (SPEC P3-6..P3-9). */
    fun resolve(containerFps: Double?, estimatedFps: Double?, isImage: Boolean = false): FpsResult = TODO()

    /** After playback started: does a later estimate still match the snapped rate? */
    fun crossCheck(snapped: Rational, estimatedFps: Double?): CrossCheck = TODO()
}
