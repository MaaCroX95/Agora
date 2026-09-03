package com.newoether.agora.viewmodel

import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.util.DebugLog
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

    fun delete(
        conversationId: String,
        expectedMessageIds: Set<String>? = null,
        onResult: (Boolean) -> Unit = {},
    ): Boolean {
        if (isDeleteLocked(conversationId)) return false
        scope.launch(ioDispatcher) {
            var deleted = false
            var selectedAtCommit = false
            try {
                withConversationLock(conversationId) {
                    // Send admission uses this same lock. Only the winner may stop live work.
                    if (isDeleteLocked(conversationId)) return@withConversationLock
                    if (
                        expectedMessageIds != null &&
                        conversations.getMessageTopologySnapshot(conversationId)
                            .mapTo(linkedSetOf()) { it.id } != expectedMessageIds
                    ) return@withConversationLock
                    selectedAtCommit = currentConversationId.value == conversationId
                    if (selectedAtCommit) stopVisibleGeneration()
                    stopLoop(conversationId)
                    conversations.deleteConversation(conversationId)
                    deleted = true
                }
                if (deleted) {
                    removeRuntime(conversationId)
                    if (selectedAtCommit) {
                        withContext(mainDispatcher) {
                            settleDeletedSelectedConversation(conversationId)
                        }
                    }
                }
            } catch (error: Exception) {
                runCatching {
                    DebugLog.e(
                        "ConversationLifecycle",
                        "Failed to delete conversation $conversationId",
                        error,
                    )
                }
            } finally {
                withContext(mainDispatcher) { onResult(deleted) }
            }
        }
        return true
    }
}
