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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusTargetModifierNode
import androidx.compose.ui.focus.Focusability
import androidx.compose.ui.focus.getFocusedRect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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

/**
 * One focus ring for the whole window: the bounds of whatever has keyboard focus (any focusable, including
 * clickables drawn without an indication) get a white outline, while [FocusRingVisibility] says the keyboard is in use.
 * SYNC5 (Compose 1.13): onFocusedBoundsChanged became a no-op; the root is now a never-focusable focus target that
 * asks for its focused descendant's rect on every draw. Each key press (and focus change) redraws it for a short
 * while, so the ring follows a row that scrolls the focused card into view.
 */
fun Modifier.desktopFocusHighlight(): Modifier = if (!isDesktop) this else this then FocusRingElement

private object FocusRingElement : ModifierNodeElement<FocusRingNode>() {
    override fun create() = FocusRingNode()
    override fun update(node: FocusRingNode) = Unit
    override fun hashCode() = 0x7f0c5
    override fun equals(other: Any?) = other === this
}

private const val FocusRingFollowMillis = 700L

private class FocusRingNode : DelegatingNode(), DrawModifierNode, PointerInputModifierNode {
    private val target = delegate(FocusTargetModifierNode(Focusability.Never) { _, _ -> follow() })
    private var followJob: Job? = null

    private fun follow() {
        if (!isAttached) return
        invalidateDraw()
        followJob?.cancel()
        followJob = coroutineScope.launch {
            val end = System.nanoTime() + FocusRingFollowMillis * 1_000_000
            while (System.nanoTime() < end) withFrameNanos { invalidateDraw() }
        }
    }

    override fun onAttach() {
        coroutineScope.launch { snapshotFlow { FocusRingVisibility.keyPresses }.collect { follow() } }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val bounds = if (FocusRingVisibility.visible) target.getFocusedRect()?.takeIf { it.width > 0f && it.height > 0f } else null
        FocusRingVisibility.lastDrawnBounds = bounds
        if (bounds == null) return
        val stroke = 2.dp.toPx()
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(bounds.left - stroke, bounds.top - stroke),
            size = Size(bounds.width + 2 * stroke, bounds.height + 2 * stroke),
            cornerRadius = CornerRadius(12.dp.toPx()),
            style = Stroke(width = stroke),
        )
    }

    // Observe (never consume) mouse presses and wheel turns anywhere in the window: they hide the ring.
    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (pass == PointerEventPass.Initial &&
            (pointerEvent.type == PointerEventType.Press || pointerEvent.type == PointerEventType.Scroll)
        ) {
            FocusRingVisibility.onPointer()
        }
    }

    override fun onCancelPointerInput() = Unit
}
