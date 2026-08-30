package com.newoether.agora.data.repository

import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.ChatEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ConversationRepositoryOpenPathTest {
    @Test
    fun branchSelectionWritesPreserveConversationRecency() = runTest {
        val dao = mockk<ChatDao>(relaxed = true)
        val conversation = ChatEntity(
            id = "conversation",
            title = "Title",
            lastUpdated = 123L,
            selectedBranchesJson = "{}",
            selectedRunBranchesJson = "{}",
        )
        coEvery { dao.getConversation(conversation.id) } returns conversation
        coEvery { dao.updateMessageBranchSelections(any(), any()) } returns 1
        coEvery { dao.updateRunBranchSelections(any(), any()) } returns 1
        coEvery { dao.updateBranchSelections(any(), any(), any()) } returns 1
        val repository = ConversationRepository(dao, database = null)

        repository.saveBranchSelections(conversation.id, mapOf(null to "message"))
        repository.saveRunBranchSelections(conversation.id, mapOf(null to "run"))
        repository.selectRunBranch(
            conversationId = conversation.id,
            parentRunId = null,
            runId = "run",
            messageSelections = mapOf(null to "message"),
        )

        coVerify(exactly = 1) {
            dao.updateMessageBranchSelections(conversation.id, "{\"null\":\"message\"}")
        }
        coVerify(exactly = 1) {
            dao.updateRunBranchSelections(conversation.id, "{\"null\":\"run\"}")
        }
        coVerify(exactly = 1) {
            dao.updateBranchSelections(
                conversation.id,
                "{\"null\":\"message\"}",
                "{\"null\":\"run\"}",
            )
        }
        coVerify(exactly = 0) { dao.updateSelectionsForRunDeletion(any(), any(), any(), any()) }
        coVerify(exactly = 0) { dao.upsertConversation(any()) }
    }

    @Test
    fun `stuck-state repair does not materialize the complete conversation graph`() = runTest {
        val dao = mockk<ChatDao>(relaxed = true)
        val repository = ConversationRepository(dao, database = null)

        repository.fixStuckMessages("conversation")

        coVerify(exactly = 0) { dao.upsertMessage(any()) }
        coVerify(exactly = 1) { dao.stopStuckMessagesForConversation("conversation") }
    }
}
