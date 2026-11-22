package com.nuvio.app.core.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * nuvio-rr fork, Phase 9 E9: Ctrl+F opens Search.
 * SYNC9 (0.1.38): upstream's own keyboard/controller navigation (PlatformKeyboardNavigationHost) takes every arrow key
 * at the AWT level and draws its own focus rings, so the fork's arrow-key focus moves and window-wide focus ring were
 * removed (the ring showed after Tab next to upstream's, in a different style).
 */
sealed interface DesktopNavigationAction {
    data object Search : DesktopNavigationAction
}

/** The action for a key event that reached the window unconsumed, or null. */
fun desktopNavigationAction(type: KeyEventType, key: Key, ctrl: Boolean, alt: Boolean, shift: Boolean): DesktopNavigationAction? {
    if (type != KeyEventType.KeyDown) return null
    if (ctrl && !alt && !shift && key == Key.F) return DesktopNavigationAction.Search
    return null
}

object DesktopShortcuts {
    private val search = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Ctrl+F: MainAppContent switches to Search and focuses its field. */
    val searchRequests: SharedFlow<Unit>
        get() = search

    fun requestSearch() {
        search.tryEmit(Unit)
    }
}
