package com.newoether.agora.ui.tasks

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.newoether.agora.data.local.TaskEntity

internal class TaskEditorSessionViewModel : ViewModel() {
    var activeTaskId by mutableStateOf<String?>(null)
        private set
    var isNew by mutableStateOf(false)
        private set
    private var baseTask by mutableStateOf<TaskEntity?>(null)

    var name by mutableStateOf("")
        private set
    var prompt by mutableStateOf("")
        private set
    var modelId by mutableStateOf<String?>(null)
        private set
    var cronExpr by mutableStateOf("")
        private set
    var runAt by mutableStateOf<Long?>(null)
        private set
    var scheduleEditorMode by mutableStateOf(ScheduleEditorMode.DAILY)
        private set
    var enabled by mutableStateOf(true)
        private set

    var detailListIndex by mutableIntStateOf(0)
        private set
    var detailListOffset by mutableIntStateOf(0)
        private set
    var historyPreview by mutableStateOf(TaskHistoryPreviewState.Idle)
        private set

    val activeTaskSnapshot: TaskEntity?
        get() = baseTask

    fun open(task: TaskEntity, isNew: Boolean) {
        if (activeTaskId == task.id) return
        activeTaskId = task.id
        baseTask = task
        this.isNew = isNew
        name = task.name
        prompt = task.prompt
        modelId = task.modelId
        cronExpr = task.cronExpr
        runAt = task.runAt
        scheduleEditorMode = initialScheduleEditorMode(task.cronExpr, task.runAt)
        enabled = task.enabled
        detailListIndex = 0
        detailListOffset = 0
        historyPreview = TaskHistoryPreviewState.Idle
    }

    fun updateName(value: String) {
        name = value
    }

    fun updatePrompt(value: String) {
        prompt = value
    }

    fun updateModelId(value: String?) {
        modelId = value
    }

    fun updateSchedule(cronExpr: String, runAt: Long?) {
        this.cronExpr = cronExpr
        this.runAt = runAt
    }

    fun updateScheduleEditorMode(value: ScheduleEditorMode) {
        scheduleEditorMode = value
    }

    fun updateEnabled(value: Boolean) {
        enabled = value
    }

    fun current(latestTask: TaskEntity? = null): TaskEntity? {
        val activeId = activeTaskId ?: return null
        val source = latestTask?.takeIf { it.id == activeId }
            ?: baseTask?.takeIf { it.id == activeId }
            ?: return null
        return source.copy(
            name = name.trim(),
            prompt = prompt,
            modelId = modelId,
            cronExpr = cronExpr,
            runAt = runAt,
            enabled = enabled,
        )
    }

    fun updateScroll(index: Int, offset: Int) {
        detailListIndex = index.coerceAtLeast(0)
        detailListOffset = offset.coerceAtLeast(0)
    }

    fun openHistory(currentConversationId: String?, isNewChatMode: Boolean) {
        val taskId = activeTaskId ?: return
        historyPreview = historyPreview.open(
            taskId = taskId,
            currentConversationId = currentConversationId,
            isNewChatMode = isNewChatMode,
        )
    }

    fun requestHistoryReturn() {
        historyPreview = historyPreview.requestReturn()
    }

    fun finishHistoryReturn() {
        historyPreview = TaskHistoryPreviewState.Idle
    }

    fun clear() {
        activeTaskId = null
        detailListIndex = 0
        detailListOffset = 0
        historyPreview = TaskHistoryPreviewState.Idle
        // Field values and the base snapshot may still be read by an outgoing animation slot, but
        // no inactive session can expose, restore, or commit them. The next open replaces them.
    }
}
