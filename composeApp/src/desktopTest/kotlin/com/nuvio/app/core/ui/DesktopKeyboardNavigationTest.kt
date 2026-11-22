package com.nuvio.app.core.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// nuvio-rr fork, Phase 9 E9 (arrow keys and the focus ring are upstream's since SYNC9)
class DesktopKeyboardNavigationTest {
    private fun action(key: Key, ctrl: Boolean = false, alt: Boolean = false, shift: Boolean = false, type: KeyEventType = KeyEventType.KeyDown) =
        desktopNavigationAction(type, key, ctrl, alt, shift)

    @Test
    fun `ctrl+F opens search`() {
        assertEquals(DesktopNavigationAction.Search, action(Key.F, ctrl = true))
        assertNull(action(Key.F))
        assertNull(action(Key.F, ctrl = true, shift = true))
        assertNull(action(Key.F, ctrl = true, type = KeyEventType.KeyUp))
    }

    @Test
    fun `arrows and other keys are left to upstream`() {
        assertNull(action(Key.DirectionLeft))
        assertNull(action(Key.DirectionDown))
        assertNull(action(Key.Tab))
        assertNull(action(Key.Enter))
    }
}
