package com.newoether.agora.data.repository

import com.newoether.agora.data.ConversationSettings
import com.newoether.agora.data.local.ConversationSettingsTransferEntity
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Completes Room-backed New Chat settings transfers into DataStore exactly once eventually. */
class ConversationSettingsTransferCoordinator(
    private val conversations: ConversationRepository,
    private val settings: SettingsRepository,
) {
    private val transferMutex = Mutex()

    suspend fun complete(conversationId: String): Boolean = transferMutex.withLock {
        settings.awaitInitialLoad()
        val transfer = conversations.getConversationSettingsTransfer(conversationId)
            ?: return@withLock false
        applyTransfer(transfer)
        true
    }

    suspend fun replayPending(): Int = transferMutex.withLock {
        val pending = try {
            settings.awaitInitialLoad()
            conversations.getPendingConversationSettingsTransfers()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            reportDeferredReplay("Unable to load pending New Chat settings transfers", error)
            return@withLock 0
        }
        var completed = 0
        pending.forEach { transfer ->
            try {
                applyTransfer(transfer)
                completed += 1
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reportDeferredReplay(
                    "Deferred New Chat settings transfer for ${transfer.conversationId}",
                    error,
                )
            }
        }
        completed
    }

    private suspend fun applyTransfer(transfer: ConversationSettingsTransferEntity) {
        val decoded = transfer.settingsJson?.let { raw ->
            Json.decodeFromString<ConversationSettings>(raw)
        }
        settings.setConversationSettingsAndAwait(transfer.conversationId, decoded)
        conversations.deleteConversationSettingsTransfer(transfer.conversationId)
    }

    private fun reportDeferredReplay(message: String, error: Exception) {
        // android.util.Log is unavailable in local JVM tests, so logging cannot affect replay.
        runCatching { DebugLog.w("ConversationSettingsTransfer", message, error) }
    }
}
