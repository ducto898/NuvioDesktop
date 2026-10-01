package com.nuvio.app.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

// nuvio-rr fork, Phase 9 E6: a mouse wheel only scrolls the page, so horizontal shelves get hover arrows on desktop.

/** How far one arrow click scrolls: most of the visible row, so one card of context stays on screen. */
internal fun shelfArrowScrollDistance(viewportWidthPx: Int): Float = viewportWidthPx * 0.8f

/** Previous / next arrows over a row; each shows only while [hovered] and only where the row can still scroll. */
@Composable
internal fun BoxScope.NuvioShelfScrollArrows(state: LazyListState, hovered: Boolean) {
    val scope = rememberCoroutineScope()
    fun scrollBy(direction: Int) {
        scope.launch { state.animateScrollBy(direction * shelfArrowScrollDistance(state.layoutInfo.viewportSize.width)) }
    }
    if (hovered && state.canScrollBackward) {
        ShelfArrow(Alignment.CenterStart, Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Scroll left", ShelfArrowPreviousTag) {
            scrollBy(-1)
        }
    }
    if (hovered && state.canScrollForward) {
        ShelfArrow(Alignment.CenterEnd, Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Scroll right", ShelfArrowNextTag) {
            scrollBy(1)
        }
    }
}

@Composable
private fun BoxScope.ShelfArrow(
    alignment: Alignment,
    icon: ImageVector,
    description: String,
    tag: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .align(alignment)
            .padding(horizontal = 8.dp)
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.65f))
            .clickable(onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(28.dp))
    }
}

internal const val ShelfArrowPreviousTag = "shelf-arrow-previous"
internal const val ShelfArrowNextTag = "shelf-arrow-next"
