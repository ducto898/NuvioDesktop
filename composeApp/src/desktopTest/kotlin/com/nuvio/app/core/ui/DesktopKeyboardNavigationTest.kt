package com.nuvio.app.core.ui

import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// nuvio-rr fork, Phase 9 E9
class DesktopKeyboardNavigationTest {
    private fun action(key: Key, ctrl: Boolean = false, alt: Boolean = false, shift: Boolean = false, type: KeyEventType = KeyEventType.KeyDown) =
        desktopNavigationAction(type, key, ctrl, alt, shift)

    @Test
    fun `plain arrows move focus`() {
        assertEquals(DesktopNavigationAction.Move(FocusDirection.Left), action(Key.DirectionLeft))
        assertEquals(DesktopNavigationAction.Move(FocusDirection.Right), action(Key.DirectionRight))
        assertEquals(DesktopNavigationAction.Move(FocusDirection.Up), action(Key.DirectionUp))
        assertEquals(DesktopNavigationAction.Move(FocusDirection.Down), action(Key.DirectionDown))
    }

    @Test
    fun `ctrl+F opens search`() {
        assertEquals(DesktopNavigationAction.Search, action(Key.F, ctrl = true))
        assertNull(action(Key.F))
    }

    @Test
    fun `modified arrows, key up and other keys are left alone`() {
        assertNull(action(Key.DirectionLeft, alt = true), "Alt+Left is back (E4)")
        assertNull(action(Key.DirectionRight, ctrl = true))
        assertNull(action(Key.DirectionDown, shift = true))
        assertNull(action(Key.DirectionDown, type = KeyEventType.KeyUp))
        assertNull(action(Key.Enter))
    }
}
