package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.navigationevent.NavigationEventDispatcher

/**
 * nuvio-rr fork, Phase 9 E4: Alt+Left and the mouse Back button feed the window's back dispatcher, the one Compose
 * already feeds with Esc and the navigation stack (NavDisplay) listens to.
 */
internal class DesktopBackInput {
    fun attach(dispatcher: NavigationEventDispatcher): Unit = TODO("Phase 9 E4 commit B")

    fun detach(): Unit = TODO("Phase 9 E4 commit B")

    /** Runs the newest enabled back handler (an overlay, a screen, then the navigation stack); no-op when detached. */
    fun back(): Unit = TODO("Phase 9 E4 commit B")

    companion object {
        val main = DesktopBackInput()

        /** Alt+Left. Not Esc (Compose maps it already) and not Backspace (it would go back from an empty text field). */
        fun isBackShortcut(type: KeyEventType, key: Key, altPressed: Boolean): Boolean = TODO("Phase 9 E4 commit B")
    }
}

@Composable
actual fun PlatformBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) = Unit
