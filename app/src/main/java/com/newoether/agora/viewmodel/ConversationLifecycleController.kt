package com.newoether.agora.viewmodel

import com.newoether.agora.data.repository.ConversationRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Coordinates durable conversation metadata changes and deletion cleanup. */
internal class ConversationLifecycleController(
    private val currentConversationId: StateFlow<String?>,
    private val conversations: ConversationRepository,
    private val scope: CoroutineScope,
    private val stopLoop: suspend (String) -> Unit,
    private val withConversationLock: suspend (String, suspend () -> Unit) -> Unit,
    private val removeRuntime: (String) -> Unit,
    private val stopVisibleGeneration: () -> Unit,
    private val settleDeletedSelectedConversation: (String) -> Unit,
    private val isDeleteLocked: (String) -> Boolean = { false },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) {
    fun rename(conversationId: String, newTitle: String) {
        scope.launch {
            conversations.updateConversationTitle(conversationId, newTitle)
        }
    }

    fun delete(conversationId: String): Boolean {
        if (isDeleteLocked(conversationId)) return false
        val wasSelected = currentConversationId.value == conversationId
        if (wasSelected) {
            stopVisibleGeneration()
        }
        scope.launch(ioDispatcher) {
            if (isDeleteLocked(conversationId)) return@launch
            stopLoop(conversationId)
            var deleted = false
            withConversationLock(conversationId) {
                if (!isDeleteLocked(conversationId)) {
                    conversations.deleteConversation(conversationId)
                    deleted = true
                }
            }
            if (!deleted) return@launch
            removeRuntime(conversationId)
            if (wasSelected) {
                withContext(mainDispatcher) {
                    settleDeletedSelectedConversation(conversationId)
                }
            }
        }
        return true
    }
}
