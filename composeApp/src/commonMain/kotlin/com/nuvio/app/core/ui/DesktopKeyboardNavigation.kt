package com.nuvio.app.core.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nuvio.app.isDesktop
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
fun desktopNavigationAction(type: KeyEventType, key: Key, ctrl: Boolean, alt: Boolean, shift: Boolean): DesktopNavigationAction? {
    if (type != KeyEventType.KeyDown) return null
    if (ctrl && !alt && !shift && key == Key.F) return DesktopNavigationAction.Search
    if (ctrl || alt || shift) return null
    val direction = when (key) {
        Key.DirectionLeft -> FocusDirection.Left
        Key.DirectionRight -> FocusDirection.Right
        Key.DirectionUp -> FocusDirection.Up
        Key.DirectionDown -> FocusDirection.Down
        else -> return null
    }
    return DesktopNavigationAction.Move(direction)
}

/**
 * Moves focus one step. Scroll containers take focus themselves (for keyboard scrolling) and report no bounds, so
 * after the move focus steps into the focused element's children until it reaches a leaf (a card, a button).
 */
fun FocusManager.moveDesktopFocus(direction: FocusDirection): Boolean {
    val moved = moveFocus(direction)
    if (moved) repeat(4) { if (!moveFocus(FocusDirection.Enter)) return true }
    return moved
}

/**
 * The focus ring shows only while the keyboard is used: an arrow key or Tab turns it on, a mouse click or wheel turns
 * it off (like a browser's :focus-visible), so it never lingers on a card while scrolling with the mouse.
 */
object FocusRingVisibility {
    var visible by mutableStateOf(false)
        private set

    fun onKey(type: KeyEventType, key: Key) {
        if (type != KeyEventType.KeyDown) return
        if (key in FocusNavigationKeys) visible = true
        if (visible) keyPresses++
    }

    /** Counts key presses while the ring shows: the ring redraws after each (focus moves without a layout change). */
    var keyPresses by mutableStateOf(0)
        private set

    /** The ring's last drawn bounds in the root's pixels, null when hidden (tests). */
    @Volatile
    internal var lastDrawnBounds: Rect? = null

    fun onPointer() {
        if (visible) visible = false
    }

    internal fun reset() {
        visible = false
    }
}

private val FocusNavigationKeys = setOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown, Key.Tab)

object DesktopShortcuts {
    private val search = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Ctrl+F: MainAppContent switches to Search and focuses its field. */
    val searchRequests: SharedFlow<Unit>
        get() = search

    fun requestSearch() {
        search.tryEmit(Unit)
    }
}

/** One focus ring for the whole window (stub). */
fun Modifier.desktopFocusHighlight(): Modifier = this
