package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState

/**
 * nuvio-rr fork, Phase 9 E4: Alt+Left feeds the window's back dispatcher, the one Compose already feeds with Esc and
 * the navigation stack (NavDisplay) listens to. The mouse Back button stays upstream's (MainAppContent pops the stack).
 */
internal class DesktopBackInput {
    private val input = DirectNavigationEventInput()
    private var dispatcher: NavigationEventDispatcher? = null  // UI thread only

    fun attach(dispatcher: NavigationEventDispatcher) {
        detach()
        dispatcher.addInput(input)
        this.dispatcher = dispatcher
    }

    fun detach() {
        dispatcher?.removeInput(input)
        dispatcher = null
    }

    /** Runs the newest enabled back handler (an overlay, a screen, then the navigation stack); no-op when detached. */
    fun back() {
        if (dispatcher != null) input.backCompleted()
    }

    companion object {
        val main = DesktopBackInput()

        /** Alt+Left. Not Esc (Compose maps it already) and not Backspace (it would go back from an empty text field). */
        fun isBackShortcut(type: KeyEventType, key: Key, altPressed: Boolean): Boolean =
            type == KeyEventType.KeyDown && altPressed && key == Key.DirectionLeft
    }
}

// nuvio-rr fork, Phase 9 E4: was a no-op on desktop; now an ordinary handler on the window's back dispatcher.
@Composable
actual fun PlatformBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) = NavigationBackHandler(
    state = rememberNavigationEventState(NavigationEventInfo.None),
    isBackEnabled = enabled,
    onBackCompleted = onBack,
)
