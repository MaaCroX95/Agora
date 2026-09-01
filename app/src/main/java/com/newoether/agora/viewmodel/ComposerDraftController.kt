package com.newoether.agora.viewmodel

import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class LoadedComposerDraft(
    val text: String,
    val attachments: List<SelectedAttachment>,
    val revision: Long,
)

data class DraftPersistResult(
    val revision: Long,
    val succeeded: Boolean,
    val matchesRequested: Boolean,
)

data class DraftClearResult(
    val attachments: List<SelectedAttachment>,
    val revision: Long,
    val succeeded: Boolean,
)

private data class PersistedComposerDraft(
    val text: String,
    val attachments: List<SelectedAttachment>,
    val revision: Long,
)

private class RepositoryComposerDraftPersistence(
    private val conversations: ConversationRepository,
) : ComposerDraftPersistence {
    override suspend fun loadDraft(ownerId: String): ConversationWorkspaceDraft {
        val entity = conversations.getConversation(ownerId)
        return ConversationWorkspaceDraft(
            text = entity?.draftText.orEmpty(),
            attachmentsJson = entity?.draftAttachments,
        )
    }

    override suspend fun updateDraft(
        ownerId: String,
        text: String,
        attachmentsJson: String?,
    ) {
        conversations.updateDraft(ownerId, text, attachmentsJson)
    }

    override suspend fun clearAcceptedDraft(ownerId: String) {
        clearAcceptedDraft(ownerId, reclaimAttachments = true)
    }

    override suspend fun clearAcceptedDraft(
        ownerId: String,
        reclaimAttachments: Boolean,
    ) {
        conversations.updateDraft(
            conversationId = ownerId,
            draftText = "",
            draftAttachments = null,
            reclaimRemovedAttachments = reclaimAttachments,
        )
    }
}

/**
 * Owns the serialized, revision-checked composer-draft cache and its durable projection.
 *
 * This controller has no generation or UI lifecycle authority. Its accepted-clear result only
 * reports attachments whose draft ownership was durably removed; the caller decides whether the
 * accepted input has another owner before asking the repository to reclaim them.
 */
internal class ComposerDraftController(
    private val persistence: ComposerDraftPersistence,
    private val conversations: ConversationRepository,
) {
    constructor(conversations: ConversationRepository) : this(
        persistence = RepositoryComposerDraftPersistence(conversations),
        conversations = conversations,
    )
    private val persistenceMutex = Mutex()
    private val persistedDrafts = mutableMapOf<String, PersistedComposerDraft>()

    /**
     * Persists one revision-checked composer snapshot. Once a write starts it is atomic with
     * respect to cancellation; newer UI snapshots wait behind the mutex instead of overtaking it.
     */
    suspend fun persist(
        conversationId: String,
        expectedRevision: Long,
        text: String,
        attachments: List<SelectedAttachment>,
        explicitlyRemovedAttachments: List<SelectedAttachment> = emptyList(),
    ): DraftPersistResult = withContext(Dispatchers.IO + NonCancellable) {
        persistenceMutex.withLock {
            val current = try {
                persistedDrafts[conversationId]
                    ?: read(conversationId).also {
                        persistedDrafts[conversationId] = it
                    }
            } catch (e: Exception) {
                DebugLog.e("ChatViewModel", "Failed to read draft for $conversationId", e)
                return@withLock DraftPersistResult(
                    revision = persistedDrafts[conversationId]?.revision ?: expectedRevision,
                    succeeded = false,
                    matchesRequested = false,
                )
            }
            if (current.revision != expectedRevision) {
                reclaimAttachments(explicitlyRemovedAttachments)
                return@withLock DraftPersistResult(
                    revision = current.revision,
                    succeeded = true,
                    matchesRequested = current.text == text && current.attachments == attachments,
                )
            }

            if (current.text == text && current.attachments == attachments) {
                reclaimAttachments(explicitlyRemovedAttachments)
                return@withLock DraftPersistResult(
                    revision = current.revision,
                    succeeded = true,
                    matchesRequested = true,
                )
            }

            try {
                val json = if (attachments.isEmpty()) null else Json.encodeToString(attachments)
                persistence.updateDraft(conversationId, text, json)
                val next = PersistedComposerDraft(
                    text = text,
                    attachments = attachments,
                    revision = current.revision + 1L,
                )
                persistedDrafts[conversationId] = next
                reclaimAttachments(current.attachments + explicitlyRemovedAttachments)
                DraftPersistResult(
                    revision = next.revision,
                    succeeded = true,
                    matchesRequested = true,
                )
            } catch (e: Exception) {
                DebugLog.e("ChatViewModel", "Failed to persist draft for $conversationId", e)
                DraftPersistResult(
                    revision = current.revision,
                    succeeded = false,
                    matchesRequested = false,
                )
            }
        }
    }

    /**
     * A successfully accepted send invalidates every older UI tail-flush by advancing the cached
     * revision only after the draft reference is durably cleared.
     */
    suspend fun clearAccepted(
        conversationId: String,
        reclaimAttachments: Boolean = true,
    ): DraftClearResult =
        withContext(Dispatchers.IO + NonCancellable) {
            persistenceMutex.withLock {
                try {
                    val current = persistedDrafts[conversationId] ?: read(conversationId)
                    persistence.clearAcceptedDraft(
                        ownerId = conversationId,
                        reclaimAttachments = reclaimAttachments,
                    )
                    val revision = current.revision + 1L
                    persistedDrafts[conversationId] = PersistedComposerDraft(
                        text = "",
                        attachments = emptyList(),
                        revision = revision,
                    )
                    DraftClearResult(
                        attachments = current.attachments,
                        revision = revision,
                        succeeded = true,
                    )
                } catch (e: Exception) {
                    DebugLog.e(
                        "ChatViewModel",
                        "Failed to clear accepted draft for $conversationId",
                        e,
                    )
                    DraftClearResult(
                        attachments = emptyList(),
                        revision = persistedDrafts[conversationId]?.revision ?: 0L,
                        succeeded = false,
                    )
                }
            }
        }

    /** Loads and revision-tags the stored draft under the same serialization boundary as writes. */
    suspend fun load(conversationId: String): LoadedComposerDraft = withContext(Dispatchers.IO) {
        persistenceMutex.withLock {
            val loaded = read(conversationId)
            persistedDrafts[conversationId] = loaded
            LoadedComposerDraft(
                text = loaded.text,
                attachments = loaded.attachments,
                revision = loaded.revision,
            )
        }
    }

    /** Releases this owner's process-local cache without changing durable draft state. */
    suspend fun evictCached(conversationId: String): Unit = withContext(Dispatchers.IO) {
        persistenceMutex.withLock {
            persistedDrafts.remove(conversationId)
            Unit
        }
    }

    private suspend fun read(conversationId: String): PersistedComposerDraft {
        val priorRevision = persistedDrafts[conversationId]?.revision ?: 0L
        val draft = persistence.loadDraft(conversationId)
        val attachments: List<SelectedAttachment> = try {
            draft.attachmentsJson
                ?.let { Json.decodeFromString<List<SelectedAttachment>>(it) }
                ?: emptyList()
        } catch (e: Exception) {
            DebugLog.w(
                "ChatViewModel",
                "Failed to deserialize draft attachments for $conversationId",
                e,
            )
            emptyList()
        }
        return PersistedComposerDraft(
            text = draft.text,
            attachments = attachments,
            revision = priorRevision,
        )
    }

    suspend fun reclaimAttachments(attachments: List<SelectedAttachment>) {
        if (attachments.isEmpty()) return
        try {
            conversations.deleteUnreferencedDraftAttachmentFiles(attachments)
        } catch (e: Exception) {
            // The durable reference update already succeeded. A cleanup failure may leak a private
            // file, but must never roll the draft back to a now-invalid attachment.
            DebugLog.w("ChatViewModel", "Failed to reclaim draft attachment files", e)
        }
    }
}
