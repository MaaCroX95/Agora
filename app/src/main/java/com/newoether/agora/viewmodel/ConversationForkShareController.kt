package com.newoether.agora.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Adapts a client's fork/share intents for the conversation it shows to typed service outcomes.
 * Every outcome (the forked conversation to open, share text, failure) goes to that origin only.
 */
internal class ConversationForkShareController(
    private val service: ConversationForkShareService,
    private val scope: CoroutineScope,
    private val forkFailureText: (String) -> String,
    private val shareFailureText: (String) -> String,
) {
    /**
     * Returns false when nothing was started. Otherwise [onResult] runs exactly once, after the
     * fork is opened (true) or its failure is reported (false), so the origin can hold its
     * confirmation until then. [onResult] may run off the main thread.
     */
    fun fork(
        origin: ChatClient,
        messageId: String? = null,
        onResult: (Boolean) -> Unit = {},
    ): Boolean {
        val conversationId = origin.openConversationId ?: return false
        scope.launch {
            var forked = false
            try {
                when (val result = service.fork(conversationId, messageId)) {
                    is ConversationForkShareService.ForkResult.Success -> {
                        origin.openConversation(result.conversationId)
                        forked = true
                    }
                    is ConversationForkShareService.ForkResult.Failure ->
                        origin.showSnackbar(forkFailureText(result.reason))
                }
            } finally {
                onResult(forked)
            }
        }
        return true
    }

    fun shareConversation(origin: ChatClient) {
        share(origin) { conversationId -> service.shareAll(conversationId) }
    }

    fun shareGeneration(origin: ChatClient, assistantMessageId: String) {
        share(origin) { conversationId -> service.shareRun(conversationId, assistantMessageId) }
    }

    fun shareMessages(origin: ChatClient, messageIds: Set<String>) {
        if (messageIds.isEmpty()) return
        share(origin) { conversationId -> service.shareMessages(conversationId, messageIds) }
    }

    private fun share(
        origin: ChatClient,
        load: suspend (conversationId: String) -> ConversationForkShareService.ShareResult,
    ) {
        val conversationId = origin.openConversationId ?: return
        scope.launch {
            when (val result = load(conversationId)) {
                is ConversationForkShareService.ShareResult.Success ->
                    origin.showShareText(result.text)
                is ConversationForkShareService.ShareResult.Failure ->
                    origin.showSnackbar(shareFailureText(result.reason))
            }
        }
    }
}
