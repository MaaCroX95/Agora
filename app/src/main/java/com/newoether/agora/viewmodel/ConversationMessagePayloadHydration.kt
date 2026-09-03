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

    fun observeSearchMessages(
        conversationId: String,
        query: String,
    ): Flow<List<ChatMessage>> =
        conversations.observeConversationSearchMatches(conversationId, query)
            .map { entities -> entities.map { entity -> entity.toUiChatMessage(appContext) } }
}
