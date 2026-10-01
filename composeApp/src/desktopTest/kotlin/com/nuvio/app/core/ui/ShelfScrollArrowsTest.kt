package com.nuvio.app.core.ui

import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// nuvio-rr fork, Phase 9 E6
@OptIn(ExperimentalTestApi::class)
class ShelfScrollArrowsTest {
    @Test
    fun `one click scrolls most of the visible row`() {
        assertEquals(800f, shelfArrowScrollDistance(1000))
        assertEquals(0f, shelfArrowScrollDistance(0))
    }

    @Test
    fun `arrows appear on hover, only where the row can scroll, and the next arrow scrolls`() = runComposeUiTest {
        lateinit var state: LazyListState
        setContent {
            state = rememberLazyListState()
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            Box(Modifier.size(width = 400.dp, height = 100.dp).hoverable(interaction).testTag("row")) {
                LazyRow(state = state) { items(30) { Spacer(Modifier.width(100.dp).size(100.dp)) } }
                NuvioShelfScrollArrows(state, hovered)
            }
        }
        onNodeWithTag(ShelfArrowNextTag).assertDoesNotExist()
        onNodeWithTag("row").performMouseInput { enter(center) }
        waitForIdle()
        onNodeWithTag(ShelfArrowPreviousTag).assertDoesNotExist()
        onNodeWithTag(ShelfArrowNextTag).performClick()
        waitForIdle()
        assertTrue(state.firstVisibleItemIndex >= 2, "scrolled past ~3 cards, at ${state.firstVisibleItemIndex}")
        onNodeWithTag(ShelfArrowPreviousTag).assertExists()
    }
}
