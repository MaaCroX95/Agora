package com.newoether.agora.ui.tasks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

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
 * overlay has completely covered the chat. Destination observation is two-step so the origin-to-
 * preview selection cannot be mistaken for leaving the preview.
 */
internal data class TaskHistoryPreviewState(
    val phase: TaskHistoryPreviewPhase = TaskHistoryPreviewPhase.IDLE,
    val taskId: String? = null,
    val originConversationId: String? = null,
    val originWasNewChat: Boolean = true,
    val previewConversationId: String? = null,
    val destinationObserved: Boolean = false,
) {
    val active: Boolean
        get() = phase != TaskHistoryPreviewPhase.IDLE

    fun open(
        taskId: String,
        previewConversationId: String,
        currentConversationId: String?,
        isNewChatMode: Boolean,
    ): TaskHistoryPreviewState =
        if (active) {
            copy(
                phase = TaskHistoryPreviewPhase.VIEWING,
                taskId = taskId,
                previewConversationId = previewConversationId,
                destinationObserved = false,
            )
        } else {
            TaskHistoryPreviewState(
                phase = TaskHistoryPreviewPhase.VIEWING,
                taskId = taskId,
                originConversationId = currentConversationId,
                originWasNewChat = isNewChatMode || currentConversationId == null,
                previewConversationId = previewConversationId,
            )
        }

    fun observeDestination(
        currentConversationId: String?,
        isNewChatMode: Boolean,
    ): TaskHistoryPreviewState {
        if (phase != TaskHistoryPreviewPhase.VIEWING) return this
        val previewId = previewConversationId ?: return this
        val previewIsSelected = !isNewChatMode && currentConversationId == previewId
        return when {
            !destinationObserved && previewIsSelected -> copy(destinationObserved = true)
            !destinationObserved -> this
            previewIsSelected -> this
            else -> Idle
        }
    }

    fun requestReturn(): TaskHistoryPreviewState =
        if (active) copy(phase = TaskHistoryPreviewPhase.RETURNING) else this

    companion object {
        val Idle = TaskHistoryPreviewState()
    }
}

/**
 * Settles Task History preview ownership only after its exact destination has first been selected.
 * New Chat or a successfully selected fork then restores ordinary Chat navigation immediately.
 */
@Composable
internal fun TaskHistoryDestinationEffect(
    editorSession: TaskEditorSessionViewModel,
    currentConversationId: String?,
    isNewChatMode: Boolean,
) {
    val preview = editorSession.historyPreview
    LaunchedEffect(editorSession, preview, currentConversationId, isNewChatMode) {
        editorSession.observeHistoryDestination(currentConversationId, isNewChatMode)
    }
}
