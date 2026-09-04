package com.newoether.agora.ui.tasks

import com.newoether.agora.automation.TaskManager
import com.newoether.agora.data.local.TaskEntity
import com.newoether.agora.model.ChatConversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskHistoryPreviewStateTest {
    @Test
    fun historyRoundTripRetainsConfigurationScrollAndOriginalConversation() {
        val session = TaskEditorSessionViewModel()
        val original = task()
        session.open(original, isNew = false)
        session.updateName("Edited name")
        session.updatePrompt("Edited prompt")
        session.updateModelId("provider:model")
        session.updateSchedule("30 9 * * *", null)
        session.updateScheduleEditorMode(ScheduleEditorMode.DAILY)
        session.updateEnabled(false)
        session.updateScroll(7, 42)

        session.openHistory(
            previewConversationId = "history-1",
            currentConversationId = "conversation-1",
            isNewChatMode = false,
        )
        assertEquals(TaskHistoryPreviewPhase.VIEWING, session.historyPreview.phase)
        assertEquals("conversation-1", session.historyPreview.originConversationId)
        assertEquals("history-1", session.historyPreview.previewConversationId)
        assertFalse(session.historyPreview.originWasNewChat)

        session.observeHistoryDestination("history-1", isNewChatMode = false)
        session.requestHistoryReturn()
        assertEquals(TaskHistoryPreviewPhase.RETURNING, session.historyPreview.phase)
        session.observeHistoryDestination("conversation-1", isNewChatMode = false)
        assertEquals(TaskHistoryPreviewPhase.RETURNING, session.historyPreview.phase)
        session.finishHistoryReturn()

        val current = requireNotNull(session.current(original.copy(nextRunAt = 999L)))
        assertEquals("Edited name", current.name)
        assertEquals("Edited prompt", current.prompt)
        assertEquals("provider:model", current.modelId)
        assertEquals("30 9 * * *", current.cronExpr)
        assertFalse(current.enabled)
        assertEquals(999L, current.nextRunAt)
        assertEquals(7, session.detailListIndex)
        assertEquals(42, session.detailListOffset)
        assertEquals(TaskHistoryPreviewState.Idle, session.historyPreview)
    }

    @Test
    fun previewIgnoresOriginUntilTargetAndSuccessfulForkSettlesIt() {
        val session = TaskEditorSessionViewModel()
        session.open(task(), isNew = false)
        session.openHistory(
            previewConversationId = "history-1",
            currentConversationId = "origin",
            isNewChatMode = false,
        )

        session.observeHistoryDestination("origin", isNewChatMode = false)
        assertFalse(session.historyPreview.destinationObserved)
        assertTrue(session.historyPreview.active)

        session.observeHistoryDestination("history-1", isNewChatMode = false)
        assertTrue(session.historyPreview.destinationObserved)
        session.observeHistoryDestination("history-1", isNewChatMode = false)
        assertTrue(session.historyPreview.active)

        session.observeHistoryDestination("fork-1", isNewChatMode = false)
        assertEquals(TaskHistoryPreviewState.Idle, session.historyPreview)
    }

    @Test
    fun newChatSettlesOnlyAfterPreviewDestinationWasObserved() {
        val session = TaskEditorSessionViewModel()
        session.open(task(), isNew = false)
        session.openHistory(
            previewConversationId = "history-1",
            currentConversationId = null,
            isNewChatMode = true,
        )

        session.observeHistoryDestination(null, isNewChatMode = true)
        assertTrue(session.historyPreview.active)

        session.observeHistoryDestination("history-1", isNewChatMode = false)
        session.observeHistoryDestination("history-1", isNewChatMode = true)
        assertEquals(TaskHistoryPreviewState.Idle, session.historyPreview)
    }

    @Test
    fun executionSnapshotIsCopiedRetainedForRoundTripAndBoundToActiveTask() {
        val session = TaskEditorSessionViewModel()
        session.open(task(), isNew = false)
        val mutableExecutions = mutableListOf(execution("history-1"))

        session.retainExecutionHistory("task-1", mutableExecutions)
        mutableExecutions += execution("history-2")
        assertEquals(listOf(execution("history-1")), session.executionHistoryFor("task-1"))

        session.openHistory(
            previewConversationId = "history-1",
            currentConversationId = "origin",
            isNewChatMode = false,
        )
        session.observeHistoryDestination("history-1", isNewChatMode = false)
        session.requestHistoryReturn()
        session.finishHistoryReturn()
        assertEquals(1, session.executionHistoryFor("task-1")?.size)

        session.open(task(id = "task-2"), isNew = false)
        assertNull(session.executionHistoryFor("task-1"))
        assertNull(session.executionHistoryFor("task-2"))
    }

    @Test
    fun reopeningTheSameActiveTaskDoesNotOverwriteActivityRecreatedDraft() {
        val session = TaskEditorSessionViewModel()
        val original = task()
        session.open(original, isNew = false)
        session.updateName("Unsaved edit")

        session.open(original.copy(name = "Room update"), isNew = false)

        assertEquals("Unsaved edit", session.name)
        assertEquals(original.id, session.activeTaskId)
    }

    @Test
    fun clearMakesTheSessionInactiveAndNormalReopenStartsFresh() {
        val session = TaskEditorSessionViewModel()
        val original = task()
        session.open(original, isNew = false)
        session.updateName("Discarded edit")
        session.updateScroll(5, 24)
        session.retainExecutionHistory(original.id, listOf(execution("history-1")))
        session.openHistory(
            previewConversationId = "history-1",
            currentConversationId = null,
            isNewChatMode = true,
        )

        session.clear()

        assertNull(session.activeTaskId)
        assertNull(session.current(original))
        assertNull(session.executionHistoryFor(original.id))
        assertEquals(0, session.detailListIndex)
        assertEquals(0, session.detailListOffset)
        assertEquals(TaskHistoryPreviewState.Idle, session.historyPreview)

        session.open(original, isNew = false)
        assertEquals(original.name, session.name)
        assertEquals(0, session.detailListIndex)
        assertEquals(0, session.detailListOffset)
    }

    @Test
    fun freshViewModelDoesNotRestoreProcessState() {
        val previousProcess = TaskEditorSessionViewModel().apply {
            open(task(), isNew = false)
            updateName("Unsaved edit")
            updateScroll(9, 81)
            retainExecutionHistory("task-1", listOf(execution("history-1")))
        }
        val newProcess = TaskEditorSessionViewModel()

        assertEquals("Unsaved edit", previousProcess.name)
        assertNull(newProcess.activeTaskId)
        assertNull(newProcess.executionHistoryFor("task-1"))
        assertEquals(0, newProcess.detailListIndex)
        assertEquals(0, newProcess.detailListOffset)
        assertEquals(TaskHistoryPreviewState.Idle, newProcess.historyPreview)
    }

    private fun task(id: String = "task-1") = TaskEntity(
        id = id,
        name = "Task",
        prompt = "Prompt",
        cronExpr = "0 9 * * *",
        nextRunAt = 123L,
        enabled = true,
    )

    private fun execution(id: String) = TaskManager.ExecutionSummary(
        conversation = ChatConversation(id = id, title = id),
        preview = id,
        status = null,
        timestamp = 123L,
    )
}
