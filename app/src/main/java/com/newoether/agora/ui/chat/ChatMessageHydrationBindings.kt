package com.newoether.agora.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.newoether.agora.data.CustomProviderConfig
import com.newoether.agora.data.forDisplay
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal data class ChatMessageHydrationBindings(
    val observeMessage: (String) -> Flow<ChatMessage?>,
    val searchMessages: (String, String) -> Flow<List<ChatMessage>>,
)

@Composable
internal fun rememberChatMessageHydrationBindings(
    viewModel: ChatViewModel,
    customProviders: List<CustomProviderConfig>,
): ChatMessageHydrationBindings = remember(viewModel, customProviders) {
    ChatMessageHydrationBindings(
        observeMessage = { messageId ->
            viewModel.messagePayloadHydration.observeMessage(messageId)
                .map { message -> message?.forDisplay(customProviders) }
        },
        searchMessages = { conversationId, query ->
            viewModel.messagePayloadHydration.observeSearchMessages(conversationId, query)
                .map { found -> found.map { message -> message.forDisplay(customProviders) } }
        },
    )
}
