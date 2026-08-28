package com.newoether.agora.ui.tasks

import com.newoether.agora.data.local.TaskEntity
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
            currentConversationId = "conversation-1",
            isNewChatMode = false,
        )
        assertEquals(TaskHistoryPreviewPhase.VIEWING, session.historyPreview.phase)
        assertEquals("conversation-1", session.historyPreview.originConversationId)
        assertFalse(session.historyPreview.originWasNewChat)

        session.requestHistoryReturn()
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
        session.openHistory(currentConversationId = null, isNewChatMode = true)

        session.clear()

        assertNull(session.activeTaskId)
        assertNull(session.current(original))
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
        }
        val newProcess = TaskEditorSessionViewModel()

        assertEquals("Unsaved edit", previousProcess.name)
        assertNull(newProcess.activeTaskId)
        assertEquals(0, newProcess.detailListIndex)
        assertEquals(0, newProcess.detailListOffset)
        assertEquals(TaskHistoryPreviewState.Idle, newProcess.historyPreview)
    }

    private fun task() = TaskEntity(
        id = "task-1",
        name = "Task",
        prompt = "Prompt",
        cronExpr = "0 9 * * *",
        nextRunAt = 123L,
        enabled = true,
    )
}
