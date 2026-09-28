package com.nuvio.app.features.player.desktop.refreshrate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ModeSelectorTest {
    private fun decide(
        fps: Double?,
        current: DisplayMode = MODE_280,
        modes: List<DisplayMode> = OWNER_MODES,
        enabled: Boolean = true,
        estimate: Double? = null,
        image: Boolean = false,
        frameCap: Double? = null,
    ) = ModeSelector.decide(enabled, fps, estimate, image, current, modes, frameCap)

    private fun assertSwitch(sel: Selection, rate: Rational, k: Long, label: String) {
        val s = assertIs<Selection.Switch>(sel, "$label: $sel")
        assertTrue(s.target.refresh.sameAs(rate), "$label: target ${s.target.refresh}, expected $rate")
        assertEquals(k, s.multiple, "$label: multiple")
    }

    private fun assertNoSwitch(sel: Selection, reason: String, label: String) {
        val n = assertIs<Selection.NoSwitch>(sel, "$label: $sel")
        assertEquals(reason, n.reason, label)
    }

    // P3-11: the brief's table (requirement 3) on the owner's monitor
    @Test
    fun `owner monitor - film and video rates go to 240 and PAL rates to 100`() {
        val r240 = Rational(239901, 1000)
        val expected240 = mapOf(
            23.976 to 10L, 24.0 to 10L, 29.97 to 8L, 30.0 to 8L,
            47.952 to 5L, 48.0 to 5L, 59.94 to 4L, 60.0 to 4L,
        )
        for ((fps, k) in expected240) assertSwitch(decide(fps), r240, k, "$fps fps")
        assertSwitch(decide(25.0), Rational(10000, 100), 4, "25 fps")
        assertSwitch(decide(50.0), Rational(10000, 100), 2, "50 fps")
    }

    // P3-11
    @Test
    fun `owner monitor - anything else stays unchanged`() {
        for (fps in listOf(23.90, 23.810, 12.0, 15.0, 100.0, 119.88, 120.0, 144.0, 1000.0, 90000.0)) {
            assertNoSwitch(decide(fps), "fps-not-standard", "$fps fps")
        }
        assertNoSwitch(decide(null), "fps-missing", "no fps")
    }

    // P3-10, P3-25: the values behind the decision are carried along
    @Test
    fun `switch result carries fps, error and candidate count`() {
        val s = assertIs<Selection.Switch>(decide(23.976))
        assertEquals(FpsSource.CONTAINER, s.fps.source)
        assertTrue(s.fps.rate.sameAs(Rational(24000, 1001)))
        // 239.901 / (10 x 24000/1001) - 1 = +587 ppm
        assertEquals(587.0, s.errorPpm, 1.0)
        assertEquals(6, s.considered, "duplicate 59.951 counts once")
    }

    // P3-12
    @Test
    fun `1000 over 1001 twins pick the matching sibling`() {
        val modes = listOf(fhd(60, 1), fhd(59940, 1000), fhd(120, 1), fhd(119880, 1000))
        val cur = fhd(60, 1)
        assertSwitch(decide(23.976, cur, modes), Rational(119880, 1000), 5, "23.976")
        assertSwitch(decide(24.0, cur, modes), Rational(120, 1), 5, "24")
        assertSwitch(decide(59.94, cur, modes), Rational(119880, 1000), 2, "59.94")
        assertSwitch(decide(60.0, cur, modes), Rational(120, 1), 2, "60")
        assertNoSwitch(decide(25.0, cur, modes), "no-suitable-mode", "25")
    }

    // P3-13
    @Test
    fun `bit depth, interlacing and resolution filter the candidates`() {
        val eightBit240 = OWNER_MODES.filter { it != MODE_240 } + qhd(239901, 1000, bpc = 8)
        assertSwitch(decide(23.976, modes = eightBit240), Rational(143973, 1000), 6, "8 bpc 240 skipped")

        val interlaced240 = OWNER_MODES.filter { it != MODE_240 } + qhd(239901, 1000, interlaced = true)
        assertSwitch(decide(23.976, modes = interlaced240), Rational(143973, 1000), 6, "interlaced 240 skipped")

        val otherRes = listOf(MODE_280, fhd(240, 1), fhd(100, 1))
        assertNoSwitch(decide(23.976, modes = otherRes), "no-suitable-mode", "1080p modes ignored")
        assertNoSwitch(decide(25.0, modes = otherRes), "no-suitable-mode", "1080p modes ignored")
    }

    // P3-13
    @Test
    fun `empty or invalid mode lists give no-suitable-mode`() {
        assertNoSwitch(decide(23.976, modes = emptyList()), "no-suitable-mode", "empty")
        val invalid = listOf(qhd(0, 1000), qhd(239901, 0), qhd(-239901, 1000), qhd(239901, -1000))
        assertNoSwitch(decide(23.976, modes = invalid), "no-suitable-mode", "invalid")
        assertNoSwitch(decide(23.976, current = qhd(0, 0)), "no-suitable-mode", "invalid current")
    }

    // P3-10 tie rule (verifier round 1): same k, smaller relative error wins
    @Test
    fun `two fitting modes at the same multiple pick the smaller error`() {
        val modes = listOf(MODE_280, qhd(119950, 1000), qhd(119900, 1000))
        assertSwitch(decide(24.0, modes = modes), Rational(119950, 1000), 5, "24 fps: 119.95 (-417 ppm) beats 119.90 (-833 ppm)")
        assertSwitch(decide(24.0, modes = modes.reversed()), Rational(119950, 1000), 5, "order does not matter")
    }

    // P3-14
    @Test
    fun `already at the target means no switch`() {
        val a = assertIs<Selection.AlreadyAtTarget>(decide(23.976, current = MODE_240))
        assertTrue(a.mode.refresh.sameAs(Rational(239901, 1000)))
        assertEquals("already-at-target", a.reason)
        // QueryDisplayConfig may report the same rate with another denominator
        assertIs<Selection.AlreadyAtTarget>(decide(23.976, current = qhd(239901000, 1000000)))
        assertIs<Selection.AlreadyAtTarget>(decide(50.0, current = MODE_100))
    }

    // P3-14
    @Test
    fun `a fitting but lower current mode still switches to the highest`() {
        assertSwitch(decide(24.0, current = MODE_120), Rational(239901, 1000), 10, "24 fps at 120")
    }

    // P3-15
    @Test
    fun `disabled wins over every other rule`() {
        assertNoSwitch(decide(23.976, enabled = false), "disabled", "normal")
        assertNoSwitch(decide(null, enabled = false), "disabled", "no fps")
        assertNoSwitch(decide(23.976, enabled = false, image = true), "disabled", "image")
        assertNoSwitch(decide(23.976, enabled = false, modes = emptyList()), "disabled", "no modes")
    }

    // P3-7..P3-9 through decide
    @Test
    fun `fps rejections pass through`() {
        assertNoSwitch(decide(23.976, estimate = 25.0), "fps-disagree", "vfr")
        assertNoSwitch(decide(23.976, image = true), "image", "image")
        assertSwitch(decide(null, estimate = 23.976), Rational(239901, 1000), 10, "estimate fallback")
    }

    // P3-25
    @Test
    fun `no-switch results explain themselves`() {
        val n = assertIs<Selection.NoSwitch>(decide(23.810))
        assertTrue("23.81" in n.detail, "detail should name the fps: ${n.detail}")
        val m = assertIs<Selection.NoSwitch>(decide(25.0, modes = listOf(MODE_280, MODE_240)))
        assertTrue(m.detail.isNotBlank(), "detail should list what was considered")
    }

    // P5-10 (Q20 a): a driver frame cap below 1.05 x target collapses display-resample (Phase 2b) ⇒ don't switch
    @Test
    fun `a frame cap below the headroom gives frame-cap, not a switch`() {
        assertNoSwitch(decide(23.976, frameCap = 200.0), "frame-cap", "old 200 cap vs 239.901")
        assertNoSwitch(decide(23.976, frameCap = 250.0), "frame-cap", "250 < 1.05 x 239.901")
        assertNoSwitch(decide(23.976, current = MODE_240, frameCap = 200.0), "frame-cap", "already at target")
    }

    @Test
    fun `a frame cap with enough headroom, off or unknown changes nothing`() {
        assertSwitch(decide(23.976, frameCap = 252.0), Rational(239901, 1000), 10, "252 >= 1.05 x 239.901")
        assertSwitch(decide(23.976, frameCap = null), Rational(239901, 1000), 10, "off / unknown")
        assertSwitch(decide(25.0, frameCap = 200.0), Rational(10000, 100), 4, "200 is plenty for 100 Hz")
        assertIs<Selection.AlreadyAtTarget>(decide(23.976, current = MODE_240, frameCap = 300.0))
    }

    @Test
    fun `earlier rules win over the frame cap and its detail explains it`() {
        assertNoSwitch(decide(23.976, enabled = false, frameCap = 200.0), "disabled", "disabled")
        assertNoSwitch(decide(null, frameCap = 200.0), "fps-missing", "no fps")
        assertNoSwitch(decide(25.0, modes = listOf(MODE_280, MODE_240), frameCap = 200.0), "no-suitable-mode", "no fitting mode")
        val n = assertIs<Selection.NoSwitch>(decide(23.976, frameCap = 200.0))
        assertTrue("200" in n.detail && "239.901" in n.detail, n.detail)
    }
}
