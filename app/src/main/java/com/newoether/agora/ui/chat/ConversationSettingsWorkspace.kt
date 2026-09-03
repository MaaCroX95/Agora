package com.newoether.agora.ui.chat

/** New Chat owns its settings even while the previous conversation id remains under transition. */
internal fun conversationSettingsOwnerId(
    isNewChatMode: Boolean,
    currentConversationId: String?,
): String? = currentConversationId.takeUnless { isNewChatMode }
