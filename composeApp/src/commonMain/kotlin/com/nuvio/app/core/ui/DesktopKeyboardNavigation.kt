package com.nuvio.app.core.ui

import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * nuvio-rr fork, Phase 9 E9: keyboard navigation on desktop. Arrow keys move focus between cards, rows and buttons
 * (only keys nothing focused used, so text fields keep their arrows); Enter opens; Ctrl+F opens Search.
 */
sealed interface DesktopNavigationAction {
    data class Move(val direction: FocusDirection) : DesktopNavigationAction
    data object Search : DesktopNavigationAction
}

/** The action for a key event that reached the window unconsumed, or null. */
fun desktopNavigationAction(type: KeyEventType, key: Key, ctrl: Boolean, alt: Boolean, shift: Boolean): DesktopNavigationAction? =
    TODO("Phase 9 E9 commit D")

object DesktopShortcuts {
    private val search = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Ctrl+F: MainAppContent switches to Search and focuses its field. */
    val searchRequests: SharedFlow<Unit>
        get() = search

    fun requestSearch() {
        search.tryEmit(Unit)
    }
}
