package com.nuvio.app.core.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventHandler
import androidx.navigationevent.NavigationEventInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// nuvio-rr fork, Phase 9 E4: Alt+Left goes back on desktop, through the same dispatcher as Esc.
class DesktopBackInputTest {
    private class Recorder(private val name: String, private val calls: MutableList<String>) :
        NavigationEventHandler<NavigationEventInfo>(NavigationEventInfo.None, true) {
        override fun onBackCompleted() {
            calls += name
        }
    }

    @Test
    fun `back runs the newest enabled handler and does nothing when detached`() {
        val calls = mutableListOf<String>()
        val dispatcher = NavigationEventDispatcher()
        val input = DesktopBackInput()
        input.back() // detached: no dispatcher, no crash
        input.attach(dispatcher)
        input.back() // no handler yet
        val screen = Recorder("screen", calls).also { dispatcher.addHandler(it) }
        val overlay = Recorder("overlay", calls).also { dispatcher.addHandler(it) }
        input.back()
        overlay.isBackEnabled = false
        input.back()
        input.detach()
        input.back()
        screen.remove()
        assertEquals(listOf("overlay", "screen"), calls)
    }

    @Test
    fun `only Alt+Left key down is the back shortcut`() {
        assertTrue(DesktopBackInput.isBackShortcut(KeyEventType.KeyDown, Key.DirectionLeft, altPressed = true))
        assertFalse(DesktopBackInput.isBackShortcut(KeyEventType.KeyUp, Key.DirectionLeft, altPressed = true))
        assertFalse(DesktopBackInput.isBackShortcut(KeyEventType.KeyDown, Key.DirectionLeft, altPressed = false))
        assertFalse(DesktopBackInput.isBackShortcut(KeyEventType.KeyDown, Key.Escape, altPressed = false))
        assertFalse(DesktopBackInput.isBackShortcut(KeyEventType.KeyDown, Key.Backspace, altPressed = false))
    }
}
