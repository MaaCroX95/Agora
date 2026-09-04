package com.newoether.agora.ui.chat

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatDrawerContentTest {
    @Test
    fun generationIndicatorHasPriorityOverUnread() {
        assertEquals(
            DrawerConversationIndicator.GENERATING,
            resolveDrawerConversationIndicator(
                isGenerating = true,
                isSelected = false,
                hasUnreadGeneration = true,
            ),
        )
    }

    @Test
    fun unreadIndicatorIsHiddenForOpenConversation() {
        assertEquals(
            DrawerConversationIndicator.NONE,
            resolveDrawerConversationIndicator(
                isGenerating = false,
                isSelected = true,
                hasUnreadGeneration = true,
            ),
        )
    }

    @Test
    fun backgroundConversationShowsUnreadIndicator() {
        assertEquals(
            DrawerConversationIndicator.UNREAD,
            resolveDrawerConversationIndicator(
                isGenerating = false,
                isSelected = false,
                hasUnreadGeneration = true,
            ),
        )
    }

    @Test
    fun edgeFadeToleranceIsTwoDp() {
        assertEquals(2.dp, DrawerEdgeFadeTolerance)
    }

    @Test
    fun topEdgeAllowsOffsetsThroughTolerance() {
        assertTrue(isDrawerListAtTop(0, 0, 2))
        assertTrue(isDrawerListAtTop(0, 2, 2))
        assertFalse(isDrawerListAtTop(0, 3, 2))
        assertFalse(isDrawerListAtTop(1, 0, 2))
    }

    @Test
    fun bottomEdgeTreatsEmptyListAsReached() {
        assertTrue(
            isDrawerListAtBottom(
                totalItemsCount = 0,
                lastVisibleItemIndex = null,
                lastVisibleItemEndOffsetPx = null,
                viewportEndOffsetPx = 100,
                tolerancePx = 2,
            ),
        )
    }

    @Test
    fun bottomEdgeAllowsFinalItemThroughTolerance() {
        assertTrue(
            isDrawerListAtBottom(
                totalItemsCount = 5,
                lastVisibleItemIndex = 4,
                lastVisibleItemEndOffsetPx = 100,
                viewportEndOffsetPx = 100,
                tolerancePx = 2,
            ),
        )
        assertTrue(
            isDrawerListAtBottom(
                totalItemsCount = 5,
                lastVisibleItemIndex = 4,
                lastVisibleItemEndOffsetPx = 102,
                viewportEndOffsetPx = 100,
                tolerancePx = 2,
            ),
        )
    }

    @Test
    fun bottomEdgeRejectsContentBeyondToleranceOrBeforeFinalItem() {
        assertFalse(
            isDrawerListAtBottom(
                totalItemsCount = 5,
                lastVisibleItemIndex = 4,
                lastVisibleItemEndOffsetPx = 103,
                viewportEndOffsetPx = 100,
                tolerancePx = 2,
            ),
        )
        assertFalse(
            isDrawerListAtBottom(
                totalItemsCount = 5,
                lastVisibleItemIndex = 3,
                lastVisibleItemEndOffsetPx = 100,
                viewportEndOffsetPx = 100,
                tolerancePx = 2,
            ),
        )
        assertFalse(
            isDrawerListAtBottom(
                totalItemsCount = 5,
                lastVisibleItemIndex = 4,
                lastVisibleItemEndOffsetPx = null,
                viewportEndOffsetPx = 100,
                tolerancePx = 2,
            ),
        )
    }
}
