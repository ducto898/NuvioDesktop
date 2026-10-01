package com.nuvio.app.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// nuvio-rr fork, Phase 9 #12: the UI scale moves in steps, so resizing the window doesn't relayout every frame.
class DesktopUiScaleStepTest {
    @Test
    fun `a window drag from 1280 to 1600 dp wide crosses only a few scale values`() {
        val values = (1280..1600).map { width -> desktopUiScaleForWindow(width.toFloat(), 1000f) }
        assertTrue(values.toSet().size <= 5, "distinct scales: ${values.toSet()}")
        assertTrue(values.zipWithNext().all { (a, b) -> b >= a }, "monotonic")
    }

    @Test
    fun `the ends are unchanged`() {
        assertEquals(1f, desktopUiScaleForWindow(1280f, 820f))
        assertEquals(1.18f, desktopUiScaleForWindow(2560f, 1440f))
        assertEquals(1f, desktopUiScaleForWindow(900f, 600f))
    }
}
