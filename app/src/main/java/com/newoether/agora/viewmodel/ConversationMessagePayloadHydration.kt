package com.newoether.agora.viewmodel

import android.content.Context
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.ChatMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal class ConversationMessagePayloadHydration(
    private val conversations: ConversationRepository,
    private val appContext: Context,
) {
    fun observeMessage(messageId: String): Flow<ChatMessage?> =
        conversations.observeMessage(messageId)
            .map { entity -> entity?.toUiChatMessage(appContext) }

    suspend fun loadMessages(
        conversationId: String,
        messageIds: List<String>,
    ): List<ChatMessage> =
        conversations.getMessagesByIds(messageIds)
            .filter { entity -> entity.conversationId == conversationId }
            .map { entity -> entity.toUiChatMessage(appContext) }
}
