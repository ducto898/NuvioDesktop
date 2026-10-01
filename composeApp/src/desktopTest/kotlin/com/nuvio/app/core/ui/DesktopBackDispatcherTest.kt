package com.nuvio.app.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// nuvio-rr fork, Phase 9 E4: Esc / Alt+Left / mouse Back go back on desktop.
class DesktopBackDispatcherTest {
    @Test
    fun `the newest enabled handler wins, disabled ones are skipped, nothing registered means not handled`() {
        val calls = mutableListOf<String>()
        val dispatcher = DesktopBackDispatcher()
        assertFalse(dispatcher.dispatch(), "no handler: the key is not consumed")
        val screen = dispatcher.register(enabled = true) { calls += "screen" }
        val dialog = dispatcher.register(enabled = true) { calls += "dialog" }
        assertTrue(dispatcher.dispatch())
        dialog.enabled = false
        assertTrue(dispatcher.dispatch())
        dispatcher.unregister(screen)
        assertFalse(dispatcher.dispatch(), "only a disabled handler left")
        assertEquals(listOf("dialog", "screen"), calls)
    }
}
