package com.newoether.agora.remote

import com.newoether.agora.model.MessageStatus
import org.junit.Assert.*
import org.junit.Test

class RemoteHistoryBudgetTest {
    private val answer = RemoteMessage("answer", "turn", null, "assistant", "Answer", 1)

    @Test fun nativeUserAuthoritySurvivesPagingButAnUnacknowledgedUserDoesNotStartTheIndicator() {
        val pending = RemoteRuntime("active", "turn")
        assertFalse(pending.hasVisibleGeneration(listOf(answer)))
        val native = pending.copy(activeTurnHasUserMessage = true)
        assertTrue(native.hasVisibleGeneration(listOf(answer)))
        assertEquals(MessageStatus.SENDING, projectRemoteMessages(listOf(answer), native).single().status)
        assertFalse(native.copy(status = "idle").hasVisibleGeneration(listOf(answer)))
        assertTrue(pending.hasVisibleGeneration(listOf(answer.copy(role = "user"))))
    }

    @Test fun retainedHistoryLimitsIncludeToolOutputAndMessageCount() {
        val tool = answer.copy(activity = RemoteActivity("tool", result = "x".repeat(8192)))
        assertThrows(RemoteContentLimitException::class.java) { checkedRemoteHistory(List(1024) { tool }) }
        assertThrows(RemoteContentLimitException::class.java) { checkedRemoteHistory(List(8193) { answer }) }
        val ordinary = List(100) { tool }
        assertSame(ordinary, checkedRemoteHistory(ordinary))
        assertEquals(RemoteFailure.CONTENT_TOO_LARGE, classifyRemoteFailure(RemoteContentLimitException()))
    }

    @Test fun pagingPreservesTheAssistantBubbleIdentity() {
        val newer = answer.copy(id = "newer", groupId = "native-group")
        val older = answer.copy(id = "older", groupId = "native-group")
        assertEquals("native-group", projectRemoteMessages(listOf(newer)).single().id)
        assertEquals("native-group", projectRemoteMessages(listOf(older, newer)).single().id)
    }
}
