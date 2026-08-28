package com.newoether.agora.ui.tasks

internal enum class TaskHistoryPreviewPhase {
    IDLE,
    VIEWING,
    RETURNING,
}

/**
 * Navigation state for a Task History conversation.
 *
 * A history conversation is a transient preview: opening another history entry must keep the
 * original chat destination, and returning to Tasks does not release the preview until the Tasks
 * overlay has completely covered the chat.
 */
internal data class TaskHistoryPreviewState(
    val phase: TaskHistoryPreviewPhase = TaskHistoryPreviewPhase.IDLE,
    val taskId: String? = null,
    val originConversationId: String? = null,
    val originWasNewChat: Boolean = true,
) {
    val active: Boolean
        get() = phase != TaskHistoryPreviewPhase.IDLE

    fun open(
        taskId: String,
        currentConversationId: String?,
        isNewChatMode: Boolean,
    ): TaskHistoryPreviewState =
        if (active) {
            copy(
                phase = TaskHistoryPreviewPhase.VIEWING,
                taskId = taskId,
            )
        } else {
            TaskHistoryPreviewState(
                phase = TaskHistoryPreviewPhase.VIEWING,
                taskId = taskId,
                originConversationId = currentConversationId,
                originWasNewChat = isNewChatMode || currentConversationId == null,
            )
        }

    fun requestReturn(): TaskHistoryPreviewState =
        if (active) copy(phase = TaskHistoryPreviewPhase.RETURNING) else this

    companion object {
        val Idle = TaskHistoryPreviewState()
    }
}
