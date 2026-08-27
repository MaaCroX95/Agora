package com.newoether.agora.data

/**
 * Resolves provider-hosted OpenAI Search for one conversation.
 *
 * The global setting is a hard master gate and defaults OFF. Once enabled globally, a conversation
 * inherits ON unless it stores an explicit override.
 */
internal fun resolveOpenAiWebSearchEnabled(
    globalEnabled: Boolean,
    conversationOverride: Boolean?,
): Boolean = globalEnabled && (conversationOverride ?: true)
