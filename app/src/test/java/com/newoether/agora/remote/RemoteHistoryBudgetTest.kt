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

    @Test fun pagingPreservesTheAssistantBubbleIdentity() {
        val newer = answer.copy(id = "newer", groupId = "native-group")
        val older = answer.copy(id = "older", groupId = "native-group")
        assertEquals("native-group", projectRemoteMessages(listOf(newer)).single().id)
        assertEquals("native-group", projectRemoteMessages(listOf(older, newer)).single().id)
    }
}
