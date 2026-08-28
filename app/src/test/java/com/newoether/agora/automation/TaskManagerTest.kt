package com.newoether.agora.automation

import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.MessageContextTopology
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.local.ProviderContextTopologySnapshot
import com.newoether.agora.data.local.TaskEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.TaskRepository
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskManagerTest {
    @Test
    fun scheduledIncompleteTaskIsDisabledWithoutCallingModel() = runTest {
        val repository = mockk<TaskRepository>()
        val conversations = mockk<ConversationRepository>()
        val engine = mockk<TaskExecutionEngine>()
        var stored = task(prompt = "")
        every { repository.getAllTasks() } returns MutableStateFlow(listOf(stored))
        coEvery { repository.getTask(stored.id) } coAnswers { stored }
        coEvery { repository.upsertTask(any()) } coAnswers { stored = firstArg() }
        val manager = TaskManager(repository, conversations, engine, backgroundScope)

        val result = manager.executeById(stored.id, "execution", stored.nextRunAt)

        assertTrue(result is TaskManager.ExecutionResult.Skipped)
        assertFalse(stored.enabled)
        assertEquals(0L, stored.nextRunAt)
        coVerify(exactly = 0) { engine.runOnce(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun incompleteDraftIsNeverPersisted() = runTest {
        val repository = mockk<TaskRepository>()
        every { repository.getAllTasks() } returns MutableStateFlow(emptyList())
        coEvery { repository.upsertTask(any()) } returns Unit
        val manager = TaskManager(
            repository,
            mockk(),
            mockk(),
            backgroundScope,
        )

        manager.saveTask(task(name = "", prompt = "Prompt"))
        manager.saveTask(task(name = "Task", prompt = ""))

        coVerify(exactly = 0) { repository.upsertTask(any()) }
    }

    @Test
    fun busyConversationIsReturnedAsAReplaySafeDeferredOccurrence() = runTest {
        val repository = mockk<TaskRepository>()
        val conversations = mockk<ConversationRepository>()
        val engine = mockk<TaskExecutionEngine>()
        val stored = task()
        every { repository.getAllTasks() } returns MutableStateFlow(listOf(stored))
        coEvery { repository.getTask(stored.id) } returns stored
        coEvery { conversations.ensureRunRecovery() } returns Unit
        coEvery { conversations.getConversation("execution") } returns null
        coEvery { conversations.upsertConversation(any()) } returns Unit
        coEvery {
            engine.runOnce("execution", stored.prompt, stored.modelId, "", true, any(), "task")
        } returns TaskExecutionEngine.Result.Busy()
        val manager = TaskManager(repository, conversations, engine, backgroundScope)

        val result = manager.executeById(stored.id, "execution")

        val deferred = result as TaskManager.ExecutionResult.Deferred
        assertEquals("execution", deferred.conversationId)
        assertEquals("Conversation is already generating", deferred.reason)
    }

    @Test
    fun successfulRecoveryLoadsOnlyTheTerminalAssistantPayload() = runTest {
        val repository = mockk<TaskRepository>()
        val conversations = mockk<ConversationRepository>()
        val engine = mockk<TaskExecutionEngine>()
        val stored = task()
        val assistant = MessageEntity(
            id = "assistant",
            conversationId = "execution",
            text = "done",
            participant = Participant.MODEL,
            status = MessageStatus.SUCCESS,
            timestamp = 2L,
            runId = "run",
            runSequence = 1L,
        )
        every { repository.getAllTasks() } returns MutableStateFlow(listOf(stored))
        coEvery { repository.getTask(stored.id) } returns stored
        coEvery { conversations.ensureRunRecovery() } returns Unit
        coEvery { conversations.getConversation("execution") } returns ChatEntity(
            id = "execution",
            title = "Task",
            taskId = stored.id,
        )
        coEvery {
            conversations.getProviderContextTopologySnapshot("execution")
        } returns ProviderContextTopologySnapshot(
            selectedBranchesJson = null,
            messages = listOf(
                MessageContextTopology(
                    id = assistant.id,
                    conversationId = assistant.conversationId,
                    parentId = null,
                    status = assistant.status,
                    participant = assistant.participant,
                    timestamp = assistant.timestamp,
                    modelName = assistant.modelName,
                    runId = assistant.runId,
                    runSequence = assistant.runSequence,
                    consumedAtPass = assistant.consumedAtPass,
                ),
            ),
        )
        coEvery { conversations.getMessage(assistant.id) } returns assistant
        coEvery {
            conversations.withProviderContextSnapshot<Any?>(any())
        } coAnswers {
            firstArg<suspend () -> Any?>().invoke()
        }
        val manager = TaskManager(repository, conversations, engine, backgroundScope)

        val result = manager.executeById(stored.id, "execution")

        val success = result as TaskManager.ExecutionResult.Success
        assertEquals("execution", success.conversationId)
        assertEquals("done", success.response)
        coVerify(exactly = 0) { engine.runOnce(any(), any(), any(), any(), any(), any()) }
    }

    private fun task(
        name: String = "Task",
        prompt: String = "Prompt",
    ) = TaskEntity(
        id = "task",
        name = name,
        prompt = prompt,
        cronExpr = "* * * * *",
        nextRunAt = 123L,
        enabled = true,
    )
}
