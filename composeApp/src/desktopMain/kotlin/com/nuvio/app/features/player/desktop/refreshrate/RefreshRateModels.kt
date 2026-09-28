package com.nuvio.app.features.player.desktop.refreshrate

// Pure decision logic for "Match display refresh rate" (fork patch, see SPEC.md Phase 3).
// No Win32, JNI, I/O, clock or logging here: Phase 4's native glue feeds it measured values
// and logs the reason codes it returns.

/** An exact rate such as 239901/1000 Hz, as DXGI and QueryDisplayConfig report it. */
data class Rational(val numerator: Long, val denominator: Long) {
    val isValid: Boolean get() = numerator > 0 && denominator > 0

    fun toDouble(): Double = numerator.toDouble() / denominator.toDouble()

    /** Exact equality of two valid rationals; false if either is invalid. */
    fun sameAs(other: Rational): Boolean = TODO()

    override fun toString(): String = "$numerator/$denominator"
}

data class DisplayMode(
    val width: Int,
    val height: Int,
    val refresh: Rational,
    val bitsPerColor: Int,
    val interlaced: Boolean = false,
)

/** What a monitor is showing right now. */
data class DisplayState(val mode: DisplayMode, val hdr: Boolean)
