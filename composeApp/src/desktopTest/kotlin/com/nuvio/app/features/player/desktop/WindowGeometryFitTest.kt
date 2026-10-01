package com.nuvio.app.features.player.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

// nuvio-rr fork, Phase 9 E9
class WindowGeometryFitTest {
    private val primary = ScreenArea(0f, 0f, 1920f, 1040f)
    private val right = ScreenArea(1920f, 0f, 2560f, 1400f)

    @Test
    fun `a window on a connected screen keeps its place`() {
        val onRight = DesktopWindowGeometry(2200f, 100f, 1280f, 820f)
        assertEquals(onRight, fitWindowGeometry(onRight, listOf(primary, right)))
        val partlyOff = DesktopWindowGeometry(1500f, 200f, 1280f, 820f) // title bar still on the primary screen
        assertEquals(partlyOff, fitWindowGeometry(partlyOff, listOf(primary)))
    }

    @Test
    fun `a window on an unplugged screen is centred on the primary screen`() {
        val onRight = DesktopWindowGeometry(2200f, 100f, 1280f, 820f)
        assertEquals(DesktopWindowGeometry(320f, 110f, 1280f, 820f), fitWindowGeometry(onRight, listOf(primary)))
    }

    @Test
    fun `a title bar above the screen top or a window too big is fixed too`() {
        val titleAbove = DesktopWindowGeometry(100f, -300f, 1280f, 820f)
        assertEquals(DesktopWindowGeometry(320f, 110f, 1280f, 820f), fitWindowGeometry(titleAbove, listOf(primary)))
        val huge = DesktopWindowGeometry(5000f, 0f, 2560f, 1400f)
        assertEquals(DesktopWindowGeometry(0f, 0f, 1920f, 1040f), fitWindowGeometry(huge, listOf(primary)))
    }

    @Test
    fun `no screen info keeps the saved geometry`() {
        val saved = DesktopWindowGeometry(5000f, 5000f, 1280f, 820f)
        assertEquals(saved, fitWindowGeometry(saved, emptyList()))
    }
}
