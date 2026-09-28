package com.nuvio.app.features.player.desktop.refreshrate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FpsSnapperTest {
    private val ntsc24 = Rational(24000, 1001)

    // P3-6
    @Test
    fun `snaps to the nearest standard rate within a tenth of a percent`() {
        val cases = mapOf(
            23.976023976 to ntsc24,
            23.976 to ntsc24,
            23.98 to ntsc24,
            24.0 to Rational(24, 1),
            24.02 to Rational(24, 1),
            25.02 to Rational(25, 1),
            29.97 to Rational(30000, 1001),
            30.0 to Rational(30, 1),
            47.952 to Rational(48000, 1001),
            48.0 to Rational(48, 1),
            50.0 to Rational(50, 1),
            59.94 to Rational(60000, 1001),
            60.0 to Rational(60, 1),
        )
        for ((fps, expected) in cases) {
            val snapped = FpsSnapper.snap(fps)
            assertTrue(snapped != null && snapped.sameAs(expected), "$fps should snap to $expected, got $snapped")
        }
    }

    // P3-6
    @Test
    fun `rates outside the standard set do not snap`() {
        for (fps in listOf(23.90, 23.810, 12.0, 15.0, 100.0, 119.88, 120.0, 144.0, 1000.0, 90000.0)) {
            assertNull(FpsSnapper.snap(fps), "$fps must not snap")
        }
        for (fps in listOf(null, Double.NaN, 0.0, -23.976, Double.POSITIVE_INFINITY)) {
            assertNull(FpsSnapper.snap(fps), "$fps must not snap")
        }
    }

    // P3-7
    @Test
    fun `container fps is used first`() {
        val r = assertIs<FpsResult.Snapped>(FpsSnapper.resolve(23.976, null))
        assertEquals(FpsSource.CONTAINER, r.source)
        assertTrue(r.rate.sameAs(ntsc24))
        assertEquals(23.976, r.input)
    }

    // P3-7
    @Test
    fun `estimate is the fallback when the container value is missing or not standard`() {
        for (container in listOf(null, Double.NaN, 0.0, -1.0, 23.810)) {
            val r = assertIs<FpsResult.Snapped>(FpsSnapper.resolve(container, 23.976), "container=$container")
            assertEquals(FpsSource.ESTIMATE, r.source)
            assertTrue(r.rate.sameAs(ntsc24))
        }
    }

    // P3-7
    @Test
    fun `no usable fps gives fps-missing or fps-not-standard`() {
        assertEquals("fps-missing", assertIs<FpsResult.Rejected>(FpsSnapper.resolve(null, null)).reason)
        assertEquals("fps-missing", assertIs<FpsResult.Rejected>(FpsSnapper.resolve(Double.NaN, 0.0)).reason)
        assertEquals("fps-not-standard", assertIs<FpsResult.Rejected>(FpsSnapper.resolve(23.810, null)).reason)
        assertEquals("fps-not-standard", assertIs<FpsResult.Rejected>(FpsSnapper.resolve(null, 13.0)).reason)
        assertEquals("fps-not-standard", assertIs<FpsResult.Rejected>(FpsSnapper.resolve(120.0, 120.0)).reason)
    }

    // P3-8
    @Test
    fun `container and estimate that disagree by more than half a percent mean no switch`() {
        val r = assertIs<FpsResult.Rejected>(FpsSnapper.resolve(23.976, 25.0))
        assertEquals("fps-disagree", r.reason)
        assertEquals(23.976, r.containerFps)
        assertEquals(25.0, r.estimatedFps)
        assertEquals("fps-disagree", assertIs<FpsResult.Rejected>(FpsSnapper.resolve(23.976, 24.1)).reason)
    }

    // P3-8
    @Test
    fun `an estimate within half a percent agrees`() {
        val r = assertIs<FpsResult.Snapped>(FpsSnapper.resolve(23.976, 24.08))
        assertEquals(FpsSource.CONTAINER, r.source)
        assertIs<FpsResult.Snapped>(FpsSnapper.resolve(23.976, Double.NaN))
    }

    // P3-8
    @Test
    fun `after-start cross-check`() {
        assertEquals(CrossCheck.AGREE, FpsSnapper.crossCheck(ntsc24, 23.976))
        assertEquals(CrossCheck.AGREE, FpsSnapper.crossCheck(ntsc24, 24.08))
        assertEquals(CrossCheck.DISAGREE, FpsSnapper.crossCheck(ntsc24, 24.1))
        assertEquals(CrossCheck.DISAGREE, FpsSnapper.crossCheck(ntsc24, 47.952))
        for (e in listOf(null, Double.NaN, 0.0, -5.0)) {
            assertEquals(CrossCheck.UNAVAILABLE, FpsSnapper.crossCheck(ntsc24, e), "estimate=$e")
        }
    }

    // P3-9
    @Test
    fun `image tracks never switch`() {
        assertEquals("image", assertIs<FpsResult.Rejected>(FpsSnapper.resolve(23.976, 23.976, isImage = true)).reason)
    }

    @Test
    fun `rational equality is exact across denominators`() {
        assertTrue(Rational(239901, 1000).sameAs(Rational(239901000, 1000000)))
        assertTrue(!Rational(239901, 1000).sameAs(Rational(239902, 1000)))
        assertTrue(!Rational(0, 1).sameAs(Rational(0, 1)))
        assertTrue(!Rational(1, 0).sameAs(Rational(1, 0)))
        assertTrue(!Rational(Long.MAX_VALUE, 1).sameAs(Rational(Long.MAX_VALUE - 1, 1)))
    }
}
