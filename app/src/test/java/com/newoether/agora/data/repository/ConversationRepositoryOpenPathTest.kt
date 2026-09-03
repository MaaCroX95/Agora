package com.newoether.agora.data.repository

import com.newoether.agora.data.local.ChatDao
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ConversationRepositoryOpenPathTest {
    @Test
    fun `stuck-state repair does not materialize the complete conversation graph`() = runTest {
        val dao = mockk<ChatDao>(relaxed = true)
        val repository = ConversationRepository(dao, database = null)

        repository.fixStuckMessages("conversation")

        coVerify(exactly = 0) { dao.upsertMessage(any()) }
        coVerify(exactly = 1) { dao.stopStuckMessagesForConversation("conversation") }
    }
}
