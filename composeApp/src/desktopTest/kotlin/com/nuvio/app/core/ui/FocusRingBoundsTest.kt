package com.nuvio.app.core.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// nuvio-rr fork, Phase 9 E9 (SYNC5): Compose 1.13 made onFocusedBoundsChanged a no-op, so the ring never drew.
@OptIn(ExperimentalTestApi::class)
class FocusRingBoundsTest {
    @Test
    fun `the ring follows keyboard focus and hides on the mouse`() = runComposeUiTest {
        val first = FocusRequester()
        val second = FocusRequester()
        FocusRingVisibility.reset()
        setContent {
            Box(Modifier.size(400.dp).desktopFocusHighlight()) {
                Box(Modifier.offset(10.dp, 20.dp).size(50.dp, 30.dp).focusRequester(first).focusable())
                Box(Modifier.offset(200.dp, 100.dp).size(80.dp, 40.dp).focusRequester(second).focusable())
            }
        }
        val px = density.density
        runOnIdle { second.requestFocus() }
        FocusRingVisibility.onKey(KeyEventType.KeyDown, Key.Tab)
        waitForIdle()
        assertEquals(Rect(200f * px, 100f * px, 280f * px, 140f * px), FocusRingVisibility.lastDrawnBounds)

        runOnIdle { first.requestFocus() }
        FocusRingVisibility.onKey(KeyEventType.KeyDown, Key.DirectionUp)
        waitForIdle()
        assertEquals(Rect(10f * px, 20f * px, 60f * px, 50f * px), FocusRingVisibility.lastDrawnBounds)

        FocusRingVisibility.onPointer()
        waitForIdle()
        assertNull(FocusRingVisibility.lastDrawnBounds)
        FocusRingVisibility.reset()
    }
}
