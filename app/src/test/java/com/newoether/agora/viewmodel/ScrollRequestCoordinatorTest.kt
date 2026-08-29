package com.newoether.agora.viewmodel

import com.newoether.agora.ui.chat.resolveMessageListTargetTop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollRequestCoordinatorTest {
    @Test
    fun `message request requires a conversation and does not consume an id when absent`() {
        val coordinator = ScrollRequestCoordinator()

        coordinator.requestMessage(null, "message")
        assertNull(coordinator.request.value)

        coordinator.requestMessage("conversation", "message")
        assertEquals(
            AnimatedScrollRequest(
                id = 1L,
                conversationId = "conversation",
                targetMessageId = "message",
            ),
            coordinator.request.value,
        )
    }

    @Test
    fun `absolute attached and top-aligned requests preserve destination and monotonic identity`() {
        val coordinator = ScrollRequestCoordinator()
        coordinator.requestMessage("conversation", null)

        coordinator.requestAbsoluteBottomAfter("conversation", "absolute")
        assertEquals(
            AnimatedScrollRequest(
                id = 2L,
                conversationId = "conversation",
                targetMessageId = "absolute",
                destination = AnimatedScrollDestination.ABSOLUTE_BOTTOM,
            ),
            coordinator.request.value,
        )

        coordinator.requestAbsoluteBottomAfter(
            conversationId = "conversation",
            messageId = "attached",
            attachedOnly = true,
        )
        assertEquals(3L, coordinator.request.value?.id)
        assertTrue(coordinator.request.value?.attachedOnly == true)
        assertTrue(coordinator.request.value?.alignToViewportTop == false)
        coordinator.requestAbsoluteBottomAfter(
            conversationId = "conversation",
            messageId = "new-chat",
            alignToViewportTop = true,
        )
        assertEquals(4L, coordinator.request.value?.id)
        assertTrue(coordinator.request.value?.attachedOnly == false)
        assertTrue(coordinator.request.value?.alignToViewportTop == true)
        assertEquals(140f, resolveMessageListTargetTop(false).value)
        assertEquals(0f, resolveMessageListTargetTop(true).value)
    }

    @Test
    fun `stale completion cannot clear a newer request and explicit clear retains flags`() {
        val coordinator = ScrollRequestCoordinator()
        coordinator.suppressNextOpenScroll = true
        coordinator.loadingDraft = true
        coordinator.requestMessage("conversation", "first")
        val staleId = coordinator.request.value!!.id
        coordinator.requestMessage("conversation", "second")

        coordinator.complete(staleId)
        assertEquals("second", coordinator.request.value?.targetMessageId)
        coordinator.complete(coordinator.request.value!!.id)
        assertNull(coordinator.request.value)

        coordinator.requestMessage("conversation", "third")
        coordinator.clear()
        assertNull(coordinator.request.value)
        assertTrue(coordinator.suppressNextOpenScroll)
        assertTrue(coordinator.loadingDraft)
    }
}
