package com.newoether.agora.viewmodel

import com.newoether.agora.data.ConversationSettings
import com.newoether.agora.data.local.ChatEntity

/** Immutable foreground destination captured synchronously when the Composer accepts a tap. */
internal data class ForegroundSendTarget(
    val ownerId: String,
    val conversationId: String,
    val runId: String,
    val wasNewChat: Boolean,
    val newChatEntryId: Long?,
    val modelId: String,
) {
    init {
        require(ownerId.isNotBlank())
        require(conversationId.isNotBlank())
        require(runId.isNotBlank())
        require(wasNewChat == (ownerId == NEW_CHAT_WORKSPACE_ID))
        require(wasNewChat == (newChatEntryId != null))
        if (!wasNewChat) require(conversationId == ownerId)
    }
}

/** Full immutable generation admission captured before attachment waiting begins. */
internal data class ForegroundSendAdmission(
    val target: ForegroundSendTarget,
    val generationSnapshot: GenerationAdmissionSnapshot,
    val newConversation: ChatEntity?,
    val newConversationSettings: ConversationSettings?,
) {
    init {
        require(generationSnapshot.conversationId == target.conversationId)
        require(generationSnapshot.runId == target.runId)
        require(newConversation == null || newConversation.id == target.conversationId)
        require(target.wasNewChat == (newConversation != null))
    }
}
