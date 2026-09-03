package com.newoether.agora.ui.chat

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageDeletionPresentationTest {
    @Test
    fun rootDeletionThatCoversEveryBranchDeletesConversation() {
        val messages = listOf(
            message("root", null),
            message("left", "root"),
            message("right", "root"),
            message("leaf", "left"),
        )

        assertTrue(deletionRemovesEntireConversation(messages, "root"))
    }

    @Test
    fun compactDeletionDeletesConversationOnlyWhenItIsTheOnlyMessage() {
        val compact = message("compact", null)
        assertTrue(
            deletionRemovesEntireConversation(
                messages = listOf(compact),
                rootMessageId = compact.id,
                compactOnly = true,
            ),
        )
        assertFalse(
            deletionRemovesEntireConversation(
                messages = listOf(compact, message("survivor", "compact")),
                rootMessageId = compact.id,
                compactOnly = true,
            ),
        )
    }

    @Test
    fun branchDeletionThatLeavesAnyMessageDoesNotDeleteConversation() {
        val messages = listOf(
            message("root", null),
            message("left", "root"),
            message("right", "root"),
        )

        assertFalse(deletionRemovesEntireConversation(messages, "left"))
        assertFalse(deletionRemovesEntireConversation(messages, "missing"))
        assertFalse(deletionRemovesEntireConversation(emptyList(), "root"))
    }

    private fun message(id: String, parentId: String?) = ChatMessage(
        id = id,
        parentId = parentId,
        text = id,
        participant = Participant.USER,
        runId = "run:$id",
    )
}
