package com.nuvio.app.core.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.onFocusedBoundsChanged
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.Modifier
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

object DesktopShortcuts {
    private val search = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Ctrl+F: MainAppContent switches to Search and focuses its field. */
    val searchRequests: SharedFlow<Unit>
        get() = search

    fun requestSearch() {
        search.tryEmit(Unit)
    }
}

/**
 * One focus ring for the whole window: the bounds of whatever has keyboard focus (any focusable, including
 * clickables drawn without an indication) get a white outline. Mouse clicks do not move focus on desktop, so it
 * shows for keyboard use; text fields show it while typing too.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.desktopFocusHighlight(): Modifier {
    if (!isDesktop) return this
    var self by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var focused by remember { mutableStateOf<LayoutCoordinates?>(null) }
    return onGloballyPositioned { self = it }
        .onFocusedBoundsChanged { focused = it }
        .drawWithContent {
            drawContent()
            val root = self ?: return@drawWithContent
            val target = focused?.takeIf { it.isAttached && root.isAttached } ?: return@drawWithContent
            val bounds = runCatching { root.localBoundingBoxOf(target, clipBounds = true) }.getOrNull() ?: return@drawWithContent
            if (bounds.width <= 0f || bounds.height <= 0f) return@drawWithContent
            val stroke = 2.dp.toPx()
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(bounds.left - stroke, bounds.top - stroke),
                size = Size(bounds.width + 2 * stroke, bounds.height + 2 * stroke),
                cornerRadius = CornerRadius(12.dp.toPx()),
                style = Stroke(width = stroke),
            )
        }
}
