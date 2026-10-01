package com.nuvio.app.core.ui

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable

// nuvio-rr fork, Phase 9 E6: a mouse wheel only scrolls the page, so horizontal shelves get hover arrows on desktop.

/** How far one arrow click scrolls: most of the visible row, so one card of context stays on screen. */
internal fun shelfArrowScrollDistance(viewportWidthPx: Int): Float = TODO("Phase 9 E6 commit B")

@Composable
internal fun BoxScope.NuvioShelfScrollArrows(state: LazyListState, hovered: Boolean): Unit = TODO("Phase 9 E6 commit B")

internal const val ShelfArrowPreviousTag = "shelf-arrow-previous"
internal const val ShelfArrowNextTag = "shelf-arrow-next"
