package com.newoether.agora.data.repository

import androidx.room.withTransaction
import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.ChatDatabase
import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.ConversationDraftAttachmentReference
import com.newoether.agora.data.local.ConversationSettingsImportTransferEntity
import com.newoether.agora.data.local.ConversationSettingsTransferEntity
import com.newoether.agora.data.local.EmbeddingEntity
import com.newoether.agora.data.local.EmbeddingModelCount
import com.newoether.agora.data.local.EmbeddingSearchRow
import com.newoether.agora.data.local.IndexableMessage
import com.newoether.agora.data.local.MessageAttachmentReference
import com.newoether.agora.data.local.MessageContextTopology
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.local.MessageStreamCheckpoint
import com.newoether.agora.data.local.NewChatDraftAttachmentReference
import com.newoether.agora.data.local.NewChatPersistEntity
import com.newoether.agora.data.local.ProviderContextTopologySnapshot
import com.newoether.agora.data.local.RunEntity
import com.newoether.agora.data.local.RunGraphCommit
import com.newoether.agora.data.local.RunBranchSelectionIntegrity
import com.newoether.agora.data.local.ToolRoundCommit
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.ChatConversation
import com.newoether.agora.model.MessagePersistenceGuard
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.RunEndReason
import com.newoether.agora.model.RunStatus
import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.model.citationRecords
import com.newoether.agora.model.matchesCitationTitle
import com.newoether.agora.util.DebugLog
import com.newoether.agora.util.AttachmentFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal fun MessageEntity.matchesCitationTitle(query: String): Boolean {
    val segments = toolCallJson?.let { raw ->
        runCatching { Json.decodeFromString<List<MessageSegment>>(raw) }.getOrNull()
    }.orEmpty()
    return segments.citationRecords(text).matchesCitationTitle(query)
}

private val citationSearchNewestFirst =
    compareByDescending<MessageEntity>(MessageEntity::timestamp).thenByDescending(MessageEntity::id)

internal suspend fun boundedCitationTitleMatches(
    query: String,
    limit: Int,
    pageSize: Int = 128,
    loadPage: suspend (afterId: String, pageSize: Int) -> List<MessageEntity>,
): List<MessageEntity> {
    if (query.isBlank() || limit <= 0) return emptyList()
    val boundedPageSize = pageSize.coerceIn(1, 256)
    val newestMatches = mutableListOf<MessageEntity>()
    var afterId = ""
    while (true) {
        val page = loadPage(afterId, boundedPageSize)
        if (page.isEmpty()) break
        page.asSequence()
            .filter { it.id > afterId && it.matchesCitationTitle(query) }
            .forEach { candidate ->
                if (newestMatches.none { it.id == candidate.id }) {
                    newestMatches += candidate
                    newestMatches.sortWith(citationSearchNewestFirst)
                    if (newestMatches.size > limit) newestMatches.removeAt(newestMatches.lastIndex)
                }
            }
        val nextAfterId = page.maxOf(MessageEntity::id)
        if (nextAfterId <= afterId) break
        afterId = nextAfterId
        if (page.size < boundedPageSize) break
    }
    return newestMatches
}

class ConversationRepository(
    private val chatDao: ChatDao,
    /** Non-null in production; null is an explicit DAO-isolated unit-test seam. */
    private val database: ChatDatabase?,
) {
    private val runRecoveryMutex = Mutex()
    @Volatile private var runRecoveryComplete = false

    suspend fun ensureRunRecovery() {
        if (runRecoveryComplete) return
        runRecoveryMutex.withLock {
            if (runRecoveryComplete) return
            val retryDelaysMs = longArrayOf(40L, 120L, 500L, 2_000L, 5_000L)
            var failureCount = 0
            while (!runRecoveryComplete) {
                try {
                    chatDao.recoverOrphanedRuns(System.currentTimeMillis())
                    runRecoveryComplete = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failureCount += 1
                    DebugLog.e(
                        "ConversationRepository",
                        "Run recovery attempt $failureCount failed; generation remains gated",
                        e,
                    )
                    delay(retryDelaysMs[(failureCount - 1).coerceAtMost(retryDelaysMs.lastIndex)])
                }
            }
        }
    }
    // ── Conversations ─────────────────────────────────────────

    private fun ChatEntity.toConversation() = ChatConversation(
        id = id, title = title, systemPromptId = systemPromptId, modelId = modelId,
        taskId = taskId, origin = origin, graduated = graduated,
        hasUnreadGeneration = hasUnreadGeneration,
        selectedBranchesJson = selectedBranchesJson,
    )

    fun getAllConversations(): Flow<List<ChatConversation>> = chatDao.getAllConversations()

    fun observeConversation(id: String): Flow<ChatConversation?> =
        chatDao.observeConversation(id).map { it?.toConversation() }

    fun observeNewChatPersist(): Flow<NewChatPersistEntity?> =
        chatDao.observeNewChatPersist()

    suspend fun getNewChatPersist(): NewChatPersistEntity? =
        chatDao.getNewChatPersist()

    suspend fun upsertNewChatPersist(entity: NewChatPersistEntity) =
        chatDao.upsertNewChatPersist(entity)

    suspend fun deleteNewChatPersist(): Boolean =
        chatDao.deleteNewChatPersist() > 0

    suspend fun getConversationSettingsTransfer(
        conversationId: String,
    ): ConversationSettingsTransferEntity? =
        chatDao.getConversationSettingsTransfer(conversationId)

    suspend fun getPendingConversationSettingsTransfers(): List<ConversationSettingsTransferEntity> =
        chatDao.getPendingConversationSettingsTransfers()

    suspend fun deleteConversationSettingsTransfer(conversationId: String): Boolean =
        chatDao.deleteConversationSettingsTransfer(conversationId) > 0

    suspend fun getConversationSettingsImportTransfer(): ConversationSettingsImportTransferEntity? =
        chatDao.getConversationSettingsImportTransfer()

    suspend fun deleteConversationSettingsImportTransfer(transferId: String): Boolean =
        chatDao.deleteConversationSettingsImportTransfer(transferId) > 0

    /** Executions spawned by [taskId], newest first — the task's execution log. */
    fun getExecutionsForTask(taskId: String): Flow<List<ChatConversation>> =
        chatDao.getExecutionsForTask(taskId).map { entities -> entities.map { it.toConversation() } }

    /** Observes message-level changes for every execution belonging to [taskId]. */
    fun observeExecutionMessagesForTask(taskId: String): Flow<List<MessageEntity>> =
        chatDao.observeExecutionMessagesForTask(taskId)

    suspend fun getConversation(id: String): ChatEntity? =
        chatDao.getConversation(id)

    suspend fun createConversation(title: String, systemPromptId: String? = null, modelId: String? = null): String {
        val id = java.util.UUID.randomUUID().toString()
        chatDao.upsertConversation(ChatEntity(id = id, title = title, systemPromptId = systemPromptId, modelId = modelId))
        return id
    }

    suspend fun upsertConversation(entity: ChatEntity) = chatDao.upsertConversation(entity)

    suspend fun updateConversationTitle(id: String, title: String): Boolean =
        chatDao.updateConversationTitle(id, title) == 1

    suspend fun setConversationUnreadGeneration(
        id: String,
        unread: Boolean,
    ): Boolean = chatDao.setConversationUnreadGeneration(id, unread) == 1

    suspend fun replaceConfiguredModelReferences(
        oldModelId: String,
        newModelId: String?,
    ) = chatDao.replaceConfiguredModelReferences(oldModelId, newModelId)

    suspend fun renameConfiguredProviderModelReferences(
        oldProvider: String,
        newProvider: String,
    ) = chatDao.renameConfiguredProviderModelReferences(oldProvider, newProvider)

    suspend fun updateConversationTitleIfUnchanged(
        id: String,
        expectedTitle: String,
        newTitle: String,
    ): Boolean = chatDao.updateConversationTitleIfUnchanged(id, expectedTitle, newTitle) == 1

    suspend fun deleteConversation(id: String) {
        val draftAttachments = chatDao.getConversation(id)?.draftAttachments
            ?.let { raw ->
                runCatching { Json.decodeFromString<List<SelectedAttachment>>(raw) }.getOrNull()
            }
            .orEmpty()
        val attachmentReferences = mutableListOf<MessageAttachmentReference>()
        var afterId: String? = null
        while (true) {
            val page = chatDao.getConversationMessageAttachmentReferencesPage(
                conversationId = id,
                afterId = afterId,
                limit = ATTACHMENT_REFERENCE_PAGE_SIZE,
            )
            attachmentReferences += page
            afterId = page.lastOrNull()?.id
            if (page.size < ATTACHMENT_REFERENCE_PAGE_SIZE) break
        }
        chatDao.deleteEmbeddingsByConversation(id)
        chatDao.deleteMessagesByConversation(id)
        chatDao.deleteConversation(id)
        deleteMessageAttachmentFiles(attachmentReferences)
        deleteUnreferencedDraftAttachmentFiles(draftAttachments)
    }

    // ── Messages ──────────────────────────────────────────────

    fun observeMessageTopology(
        conversationId: String,
    ): Flow<List<MessageContextTopology>> =
        chatDao.observeMessageContextTopology(conversationId)

    suspend fun getMessageTopologySnapshot(
        conversationId: String,
    ): List<MessageContextTopology> =
        chatDao.getMessageContextTopology(conversationId)

    fun observeMessage(messageId: String): Flow<MessageEntity?> =
        chatDao.observeMessage(messageId)


    suspend fun getProviderContextTopologySnapshot(
        conversationId: String,
    ): ProviderContextTopologySnapshot? =
        chatDao.getProviderContextTopologySnapshot(conversationId)

    /** Keeps topology and every selected payload row on one immutable Room read snapshot. */
    suspend fun <T> withProviderContextSnapshot(block: suspend () -> T): T =
        database?.withTransaction { block() } ?: block()

    suspend fun getMessage(messageId: String): MessageEntity? =
        chatDao.getMessage(messageId)

    suspend fun getContextMessagesByIds(ids: List<String>): List<MessageEntity> =
        getMessagesByIds(ids)

    suspend fun getLastMessageForConversation(conversationId: String): MessageEntity? =
        chatDao.getLastMessageForConversation(conversationId)

    suspend fun upsertMessage(entity: MessageEntity) {
        require(entity.runId.isNotBlank()) { "Message ${entity.id} has no Run" }
        require(entity.runSequence >= 0) { "Message ${entity.id} has no Run sequence" }
        chatDao.upsertMessage(entity)
    }

    suspend fun createRunWithMessages(
        run: RunEntity,
        messages: List<MessageEntity>,
        messageSelectionUpdates: Map<String?, String>,
        conversationModelId: String,
        at: Long = System.currentTimeMillis(),
    ): RunGraphCommit {
        ensureRunRecovery()
        return chatDao.createRunWithMessages(
            run,
            messages,
            messageSelectionUpdates,
            conversationModelId,
            at,
        )
    }

    suspend fun createConversationRunWithMessages(
        conversation: ChatEntity,
        run: RunEntity,
        messages: List<MessageEntity>,
        messageSelectionUpdates: Map<String?, String>,
        conversationModelId: String,
        conversationSettingsJson: String?,
        at: Long = System.currentTimeMillis(),
    ): RunGraphCommit {
        ensureRunRecovery()
        return chatDao.createConversationRunWithMessages(
            conversation,
            run,
            messages,
            messageSelectionUpdates,
            conversationModelId,
            conversationSettingsJson,
            at,
        )
    }

    suspend fun importRunGraph(runs: List<RunEntity>, messages: List<MessageEntity>) =
        chatDao.importRunGraph(runs, messages)

    suspend fun createForkGraph(
        conversation: ChatEntity,
        runs: List<RunEntity>,
        messages: List<MessageEntity>,
        sourceToForkMessageIds: Map<String, String>,
    ) = chatDao.createForkGraph(conversation, runs, messages, sourceToForkMessageIds)

    suspend fun appendToolRoundToRun(
        messages: List<MessageEntity>,
        expectedPass: Int,
    ): ToolRoundCommit {
        ensureRunRecovery()
        require(messages.isNotEmpty() && messages.all { it.runId.isNotBlank() })
        return chatDao.appendToolRoundToRun(messages, expectedPass)
    }

    suspend fun getRun(runId: String): RunEntity? = chatDao.getRun(runId)

    fun getRunsForConversation(conversationId: String): Flow<List<RunEntity>> =
        chatDao.getRunsForConversation(conversationId)

    suspend fun getRunsForConversationSnapshot(conversationId: String): List<RunEntity> =
        chatDao.getRunsForConversationSnapshot(conversationId)

    suspend fun getMessagesForRuns(runIds: List<String>): List<MessageEntity> =
        if (runIds.isEmpty()) emptyList() else chatDao.getMessagesForRuns(runIds)

    suspend fun deleteMessageSubtree(
        conversationId: String,
        rootMessageId: String,
        staleMessageIds: List<String>,
        rootRunIdsToDelete: List<String>,
        messageSelections: Map<String?, String>,
        runSelections: Map<String?, String>,
        at: Long = System.currentTimeMillis(),
    ): Boolean = chatDao.deleteMessageSubtree(
        conversationId = conversationId,
        rootMessageId = rootMessageId,
        staleMessageIds = staleMessageIds,
        rootRunIdsToDelete = rootRunIdsToDelete,
        selectedBranchesJson = Json.encodeToString(messageSelections.mapKeys { it.key ?: "null" }),
        selectedRunBranchesJson = Json.encodeToString(runSelections.mapKeys { it.key ?: "null" }),
        at = at,
    )

    suspend fun getLiveRun(conversationId: String): RunEntity? =
        chatDao.getLiveRun(conversationId)

    suspend fun requestRunStop(runId: String, at: Long = System.currentTimeMillis()): Boolean =
        chatDao.markRunStopping(runId, at) == 1

    suspend fun finishRunStopped(
        runId: String,
        reason: RunEndReason = RunEndReason.USER_STOPPED,
        at: Long = System.currentTimeMillis(),
    ): Boolean = chatDao.terminalizeLiveRun(runId, RunStatus.STOPPED, reason, at) == 1

    suspend fun failRun(runId: String, at: Long = System.currentTimeMillis()): Boolean =
        chatDao.terminalizeLiveRun(
            runId,
            RunStatus.FAILED,
            RunEndReason.PROVIDER_ERROR,
            at,
        ) == 1

    /**
     * Persist the mutable portion of an in-flight model message without creating a missing row.
     * Returns false when the placeholder was deleted while generation was still unwinding.
     */
    suspend fun updateStreamingMessageCheckpoint(message: ChatMessage): Boolean =
        chatDao.updateMessageCheckpoint(message.toStreamCheckpoint()) > 0

    /** Atomically persists a terminal model snapshot and terminalizes its Run. */
    suspend fun finishGeneration(
        message: ChatMessage,
        conversationId: String,
        runId: String,
        status: RunStatus,
        reason: RunEndReason,
        markConversationUnread: Boolean = false,
        at: Long = System.currentTimeMillis(),
    ): Boolean = chatDao.finishGeneration(
        checkpoint = message.toStreamCheckpoint(),
        conversationId = conversationId,
        runId = runId,
        status = status,
        reason = reason,
        at = at,
        markConversationUnread = markConversationUnread,
    )

    /** Atomically persists the final stopped snapshot(s) and terminalizes their Run. */
    suspend fun finishStoppedGeneration(
        messages: List<ChatMessage>,
        runId: String?,
        at: Long = System.currentTimeMillis(),
    ): Boolean {
        val run = runId?.let { chatDao.getRun(it) }
        val conversationId = run?.conversationId
            ?: messages.firstNotNullOfOrNull { message ->
                chatDao.getMessage(message.id)?.conversationId
            }
        // Conversation/Run deletion is itself a durable terminal outcome.
        if (conversationId == null || chatDao.getConversation(conversationId) == null) {
            return runId == null || run == null
        }
        val applied = chatDao.finishStoppedGeneration(
            checkpoints = messages.map {
                it.copy(status = MessageStatus.STOPPED).toStreamCheckpoint()
            },
            runId = runId,
            at = at,
        )
        if (applied) return true
        // Idempotent retry: a previous attempt may have committed but its caller was cancelled
        // before observing the result.
        return runId == null || chatDao.getRun(runId)?.status?.isTerminal != false
    }

    private fun ChatMessage.toStreamCheckpoint(): MessageStreamCheckpoint {
        val persistedSegments = segments?.takeIf { it.isNotEmpty() } ?: toolCall?.let {
            listOf(
                MessageSegment(
                    type = "tool",
                    toolName = it.toolName,
                    toolArgs = it.arguments,
                    toolResult = it.result,
                    signature = it.signature,
                    toolCallId = it.toolCallId,
                    responseOutputItems = it.responseOutputItems,
                    responseOutputItemProvider = it.responseOutputItemProvider,
                )
            )
        }
        return MessageStreamCheckpoint(
            id = id,
            text = MessagePersistenceGuard.clipText(text),
            images = images,
            thoughts = thoughts?.let(MessagePersistenceGuard::clipText),
            thoughtTitle = thoughtTitle,
            tokenCount = tokenCount,
            inputTokenCount = tokenUsage?.inputTokenCount,
            cachedInputTokenCount = tokenUsage?.cachedInputTokenCount,
            cacheWriteInputTokenCount = tokenUsage?.cacheWriteInputTokenCount,
            uncachedInputTokenCount = tokenUsage?.uncachedInputTokenCount,
            outputTokenCount = tokenUsage?.outputTokenCount,
            reasoningTokenCount = tokenUsage?.reasoningTokenCount,
            status = status,
            thoughtTimeMs = thoughtTimeMs,
            toolCallJson = MessagePersistenceGuard.encodeSegmentsBounded(persistedSegments),
        )
    }

    suspend fun deleteMessagesByIds(ids: List<String>) = chatDao.deleteMessagesByIds(ids)

    suspend fun beginRecompactContextCompact(
        replacementRun: RunEntity,
        messageId: String,
        modelName: String,
        expectedSelections: Map<String?, String>,
    ): MessageEntity = chatDao.beginRecompactContextCompact(
        replacementRun = replacementRun,
        messageId = messageId,
        modelName = modelName,
        expectedSelectedBranchesJson = Json.encodeToString(
            expectedSelections.mapKeys { it.key ?: "null" },
        ),
    )


    suspend fun removeContextCompact(messageId: String): Boolean = chatDao.removeContextCompact(messageId)

    suspend fun getMessagesByIds(ids: List<String>): List<MessageEntity> =
        ids.chunked(CONTEXT_MESSAGE_QUERY_PAGE_SIZE).flatMap { page ->
            chatDao.getMessagesByIds(page)
        }

    suspend fun getSearchableMessagesByIds(ids: List<String>): List<MessageEntity> =
        if (ids.isEmpty()) emptyList() else chatDao.getSearchableMessagesByIds(ids)

    suspend fun isMessageSearchable(messageId: String): Boolean =
        chatDao.isMessageSearchable(messageId)

    // ── Branch Selection ──────────────────────────────────────

    suspend fun saveBranchSelections(
        conversationId: String,
        selections: Map<String?, String>,
    ) = withContext(Dispatchers.Default) {
        val conversation = chatDao.getConversation(conversationId) ?: return@withContext
        val stringKeyMap = selections.mapKeys { it.key ?: "null" }
        val json = Json.encodeToString(stringKeyMap)
        if (conversation.selectedBranchesJson != json) {
            chatDao.upsertConversation(conversation.copy(selectedBranchesJson = json, lastUpdated = System.currentTimeMillis()))
        }
    }

    suspend fun restoreBranchSelections(
        conversationId: String,
    ): Map<String?, String> = withContext(Dispatchers.Default) {
        val conversation = chatDao.getConversation(conversationId)
            ?: return@withContext emptyMap()
        val raw = conversation.selectedBranchesJson ?: return@withContext emptyMap()
        try {
            val map = Json.decodeFromString<Map<String, String>>(raw)
            map.mapKeys { if (it.key == "null") null else it.key }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    suspend fun saveRunBranchSelections(
        conversationId: String,
        selections: Map<String?, String>,
    ) = withContext(Dispatchers.Default) {
        val conversation = chatDao.getConversation(conversationId) ?: return@withContext
        val stored = Json.encodeToString(selections.mapKeys { it.key ?: "null" })
        if (conversation.selectedRunBranchesJson != stored) {
            chatDao.upsertConversation(
                conversation.copy(
                    selectedRunBranchesJson = stored,
                    lastUpdated = System.currentTimeMillis(),
                )
            )
        }
    }

    suspend fun restoreRunBranchSelections(
        conversationId: String,
    ): Map<String?, String> = withContext(Dispatchers.Default) {
        val raw = chatDao.getConversation(conversationId)?.selectedRunBranchesJson
            ?: return@withContext emptyMap()
        runCatching {
            Json.decodeFromString<Map<String, String>>(raw)
                .mapKeys { if (it.key == "null") null else it.key }
        }.getOrDefault(emptyMap())
    }

    /**
     * Removes impossible parent->child Run-selection edges left by the historical v17->v18 data
     * repair. The compare-and-set protects a concurrent user branch selection, and the update does
     * not touch conversation recency because selection metadata is derived from the Run tree.
     */
    suspend fun repairInvalidRunBranchSelections(): Int {
        var repairedConversations = 0
        chatDao.getAllConversationsList().forEach { conversation ->
            val raw = conversation.selectedRunBranchesJson ?: return@forEach
            val decoded = runCatching {
                Json.decodeFromString<Map<String, String>>(raw)
                    .mapKeys { if (it.key == "null") null else it.key }
            }.getOrNull() ?: return@forEach
            val repaired = RunBranchSelectionIntegrity.retainValidEdges(
                selections = decoded,
                runs = chatDao.getRunsForConversationSnapshot(conversation.id),
            )
            if (repaired == decoded) return@forEach
            val replacement = Json.encodeToString(repaired.mapKeys { it.key ?: "null" })
            repairedConversations += chatDao.compareAndSetRunBranchSelections(
                conversationId = conversation.id,
                expected = raw,
                replacement = replacement,
            )
        }
        return repairedConversations
    }

    suspend fun selectRunBranch(
        conversationId: String,
        parentRunId: String?,
        runId: String,
    ) {
        val selections = restoreRunBranchSelections(conversationId).toMutableMap()
        selections[parentRunId] = runId
        saveRunBranchSelections(conversationId, selections)
    }

    /** Persists Run and legacy message selection maps in the same row update. */
    suspend fun selectRunBranch(
        conversationId: String,
        parentRunId: String?,
        runId: String,
        messageSelections: Map<String?, String>,
        at: Long = System.currentTimeMillis(),
    ) = withContext(Dispatchers.Default) {
        val runSelections = restoreRunBranchSelections(conversationId).toMutableMap()
        runSelections[parentRunId] = runId
        check(
            chatDao.updateSelectionsForRunDeletion(
                conversationId = conversationId,
                selectedBranchesJson = Json.encodeToString(messageSelections.mapKeys { it.key ?: "null" }),
                selectedRunBranchesJson = Json.encodeToString(runSelections.mapKeys { it.key ?: "null" }),
                at = at,
            ) == 1
        ) { "Conversation $conversationId disappeared during branch selection" }
    }

    // ── Stuck Message Fixer ───────────────────────────────────

    suspend fun fixStuckMessages(conversationId: String) {
        chatDao.stopStuckMessagesForConversation(conversationId)
    }

    // ── Embeddings ────────────────────────────────────────────

    suspend fun deleteEmbeddingsByConversation(conversationId: String) =
        chatDao.deleteEmbeddingsByConversation(conversationId)

    suspend fun deleteOrphanedEmbeddings() =
        chatDao.deleteOrphanedEmbeddings()

    suspend fun deleteEmbeddingsByModel(modelId: String) =
        chatDao.deleteEmbeddingsByModel(modelId)

    suspend fun upsertEmbedding(entity: EmbeddingEntity) =
        chatDao.upsertEmbedding(entity)

    suspend fun upsertEmbeddingIfSearchable(entity: EmbeddingEntity): Boolean =
        chatDao.upsertEmbeddingIfSearchable(entity)

    suspend fun deleteAllConversations() =
        chatDao.deleteAllConversations()

    suspend fun findExistingMessageIds(ids: List<String>): List<String> =
        chatDao.findExistingMessageIds(ids)

    suspend fun getEmbeddingSearchPage(
        modelId: String,
        afterId: Long,
        minimumTextLength: Int,
        limit: Int,
    ): List<EmbeddingSearchRow> = chatDao.getEmbeddingSearchPage(
        modelId = modelId,
        afterId = afterId,
        minimumTextLength = minimumTextLength,
        limit = limit,
    )

    suspend fun deleteEmbedding(messageId: String) =
        chatDao.deleteEmbedding(messageId)

    suspend fun getEmbeddingCountByModel(modelId: String): Int =
        chatDao.getEmbeddingCountByModel(modelId)

    suspend fun getEmbeddingCountsByModels(modelIds: List<String>): List<EmbeddingModelCount> =
        chatDao.getEmbeddingCountsByModels(modelIds)

    suspend fun getIndexableMessageCount(): Int =
        chatDao.getIndexableMessageCount()

    suspend fun getUnembeddedMessagesPage(
        modelId: String,
        afterId: String?,
        limit: Int,
    ): List<IndexableMessage> =
        chatDao.getUnembeddedMessagesPage(modelId, afterId, limit)

    // ── Search ────────────────────────────────────────────────

    suspend fun searchMessages(query: String, limit: Int = 10): List<MessageEntity> {
        if (limit <= 0) return emptyList()
        val directMatches = chatDao.searchMessages(escapeLikePattern(query), limit)
        val citationMatches = boundedCitationTitleMatches(query, limit) { afterId, pageSize ->
            chatDao.getMessagesWithCitationSegmentsPage(afterId, pageSize)
        }
        return (directMatches + citationMatches)
            .distinctBy(MessageEntity::id)
            .sortedWith(citationSearchNewestFirst)
            .take(limit)
    }

    /** Escapes LIKE wildcards so a literal "%"/"_" in the user's query matches itself
     *  instead of matching everything (paired with ESCAPE '\' in the DAO query). */
    private fun escapeLikePattern(query: String): String =
        query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    suspend fun getAllConversationsList(): List<ChatEntity> =
        chatDao.getAllConversationsList()

    suspend fun getSearchableConversation(id: String): ChatEntity? =
        chatDao.getSearchableConversation(id)

    suspend fun getSearchableConversationCount(): Int =
        chatDao.getSearchableConversationCount()

    suspend fun getSearchableConversationsPage(
        offset: Int,
        limit: Int,
        descending: Boolean,
    ): List<ChatEntity> = if (descending) {
        chatDao.getSearchableConversationsPageDescending(offset, limit)
    } else {
        chatDao.getSearchableConversationsPageAscending(offset, limit)
    }

    suspend fun getMessageAttachmentReferencesPage(
        afterId: String?,
        limit: Int,
    ): List<MessageAttachmentReference> =
        chatDao.getMessageAttachmentReferencesPage(afterId, limit)

    suspend fun getConversationDraftAttachmentReferencesPage(
        afterId: String?,
        limit: Int,
    ): List<ConversationDraftAttachmentReference> =
        chatDao.getConversationDraftAttachmentReferencesPage(afterId, limit)

    suspend fun getNewChatDraftAttachmentReference(): NewChatDraftAttachmentReference? =
        chatDao.getNewChatDraftAttachmentReference()

    /** Persists the composer draft (text + serialized attachments) for a conversation. */
    suspend fun updateDraft(conversationId: String, draftText: String, draftAttachments: String?) {
        chatDao.updateDraft(conversationId, draftText, draftAttachments)
    }

    /** Deletes candidate files only after verifying that no remaining message or draft still
     * references them. This protects legacy forks/imports that share old backing paths. */
    suspend fun deleteMessageFiles(messages: List<MessageEntity>) {
        deleteUnreferencedAttachmentFiles(
            messages.flatMapTo(linkedSetOf()) { it.attachmentFilePaths() }
        )
    }

    private suspend fun deleteMessageAttachmentFiles(
        messages: List<MessageAttachmentReference>,
    ) {
        deleteUnreferencedAttachmentFiles(
            messages.flatMapTo(linkedSetOf()) { it.attachmentFilePaths() }
        )
    }

    /** Reclaims private composer files after their draft reference has been durably removed. */
    suspend fun deleteUnreferencedDraftAttachmentFiles(
        attachments: List<SelectedAttachment>,
    ) {
        val reclaimable = attachments.filter { it.storage.reclaimWhenAbandoned }
        deleteUnreferencedAttachmentFiles(
            reclaimable.flatMapTo(linkedSetOf()) { attachment ->
                buildList {
                    attachment.localPath?.let(::add)
                    addAll(attachment.processedFrames.orEmpty())
                    addAll(attachment.preRenderedPaths.orEmpty())
                }
            },
        )
        AttachmentFiles.deleteEmptySandboxParents(reclaimable)
    }

    private suspend fun deleteUnreferencedAttachmentFiles(candidates: Set<String>) {
        if (candidates.isEmpty()) return
        val referenced = linkedSetOf<String>()
        var afterMessageId: String? = null
        while (true) {
            val page = chatDao.getMessageAttachmentReferencesPage(
                afterId = afterMessageId,
                limit = ATTACHMENT_REFERENCE_PAGE_SIZE,
            )
            page.forEach { reference ->
                reference.images.mapTo(referenced, ::normalizeAttachmentPath)
                reference.attachmentMeta
                    ?.let { raw ->
                        runCatching { Json.decodeFromString<AttachmentMeta>(raw) }.getOrNull()
                    }
                    ?.items
                    ?.mapNotNullTo(referenced) { item ->
                        item.originalUri
                            ?.takeIf { it.startsWith("file://") }
                            ?.let(::normalizeAttachmentPath)
                    }
            }
            afterMessageId = page.lastOrNull()?.id
            if (page.size < ATTACHMENT_REFERENCE_PAGE_SIZE) break
        }

        var afterConversationId: String? = null
        while (true) {
            val page = chatDao.getConversationDraftAttachmentReferencesPage(
                afterId = afterConversationId,
                limit = ATTACHMENT_REFERENCE_PAGE_SIZE,
            )
            page.forEach { reference ->
                runCatching {
                    Json.decodeFromString<List<SelectedAttachment>>(reference.draftAttachments)
                }.getOrNull()?.forEach { attachment ->
                    attachment.localPath?.let { referenced += normalizeAttachmentPath(it) }
                    attachment.processedFrames.orEmpty()
                        .mapTo(referenced, ::normalizeAttachmentPath)
                    attachment.preRenderedPaths.orEmpty()
                        .mapTo(referenced, ::normalizeAttachmentPath)
                }
            }
            afterConversationId = page.lastOrNull()?.id
            if (page.size < ATTACHMENT_REFERENCE_PAGE_SIZE) break
        }

        chatDao.getNewChatDraftAttachmentReference()?.let { reference ->
            runCatching {
                Json.decodeFromString<List<SelectedAttachment>>(reference.draftAttachments)
            }.getOrNull()?.forEach { attachment ->
                attachment.localPath?.let { referenced += normalizeAttachmentPath(it) }
                attachment.processedFrames.orEmpty()
                    .mapTo(referenced, ::normalizeAttachmentPath)
                attachment.preRenderedPaths.orEmpty()
                    .mapTo(referenced, ::normalizeAttachmentPath)
            }
        }

        candidates
            .asSequence()
            .map(::normalizeAttachmentPath)
            .filterNot(referenced::contains)
            .forEach { path -> runCatching { java.io.File(path).delete() } }
    }

    private fun MessageEntity.attachmentFilePaths(): List<String> =
        attachmentFilePaths(images, attachmentMeta)

    private fun MessageAttachmentReference.attachmentFilePaths(): List<String> =
        attachmentFilePaths(images, attachmentMeta)

    private fun attachmentFilePaths(
        images: List<String>,
        attachmentMeta: String?,
    ): List<String> = buildList {
        images.mapTo(this, ::normalizeAttachmentPath)
        attachmentMeta
            ?.let { raw -> runCatching { Json.decodeFromString<AttachmentMeta>(raw) }.getOrNull() }
            ?.items
            ?.mapNotNullTo(this) { item ->
                item.originalUri
                    ?.takeIf { it.startsWith("file://") }
                    ?.let(::normalizeAttachmentPath)
            }
    }

    private fun normalizeAttachmentPath(path: String): String {
        val raw = path.removePrefix("file://")
        return runCatching { java.io.File(raw).canonicalPath }
            .getOrElse { java.io.File(raw).absolutePath }
    }

    private companion object {
        const val ATTACHMENT_REFERENCE_PAGE_SIZE = 128
        const val CONTEXT_MESSAGE_QUERY_PAGE_SIZE = 64
    }
}
