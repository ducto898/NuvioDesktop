package com.nuvio.app.features.player.desktop.refreshrate

// Pure decision logic for "Match display refresh rate" (fork patch, see SPEC.md Phase 3).
// No Win32, JNI, I/O, clock or logging here: Phase 4's native glue feeds it measured values
// and logs the reason codes it returns.

/** An exact rate such as 239901/1000 Hz, as DXGI and QueryDisplayConfig report it. */
data class Rational(val numerator: Long, val denominator: Long) {
    val isValid: Boolean get() = numerator > 0 && denominator > 0

    fun toDouble(): Double = numerator.toDouble() / denominator.toDouble()

    /** Exact equality of two valid rationals; false if either is invalid. */
    fun sameAs(other: Rational): Boolean {
        if (!isValid || !other.isValid) return false
        // 128-bit cross multiplication: a/b == c/d  <=>  a*d == c*b, without overflow.
        return Math.multiplyHigh(numerator, other.denominator) == Math.multiplyHigh(other.numerator, denominator) &&
            numerator * other.denominator == other.numerator * denominator
    }

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

/** |a/b - 1| for two rates; NaN if either is invalid. */
internal fun relativeError(a: Double, b: Double): Double =
    if (a.isFinite() && b.isFinite() && a > 0.0 && b > 0.0) kotlin.math.abs(a / b - 1.0) else Double.NaN

internal fun DisplayMode.describe(): String =
    "${width}x$height@$refresh ${bitsPerColor}bpc${if (interlaced) " interlaced" else ""}"
