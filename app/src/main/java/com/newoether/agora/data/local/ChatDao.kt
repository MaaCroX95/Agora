package com.newoether.agora.data.local

import androidx.room.*
import com.newoether.agora.model.ChatConversation
import com.newoether.agora.model.ConversationCommand
import com.newoether.agora.model.ConversationRuntimeReducer
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.RunEffect
import com.newoether.agora.model.RunEndReason
import com.newoether.agora.model.RunRecoveryPolicy
import com.newoether.agora.model.RunRecoverySnapshot
import com.newoether.agora.model.RunState
import com.newoether.agora.model.RunStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private fun decodeSelectionMap(raw: String?): MutableMap<String?, String> =
    raw?.let {
        runCatching {
            Json.decodeFromString<Map<String, String>>(it)
                .mapKeysTo(mutableMapOf()) { entry ->
                    if (entry.key == "null") null else entry.key
                }
        }.getOrDefault(mutableMapOf())
    } ?: mutableMapOf()

private fun encodeSelectionMap(selections: Map<String?, String>): String =
    Json.encodeToString(selections.mapKeys { it.key ?: "null" })

data class EmbeddingModelCount(
    val modelId: String,
    val count: Int,
)

@Dao
interface ChatDao : ChatAutomationDao, ChatContextCompactDao, ChatProviderContextDao {
    // Task executions always remain in their owning Task's History.
    @Query("SELECT id, title, systemPromptId, modelId, taskId, origin, graduated, hasUnreadGeneration, selectedBranchesJson FROM conversations WHERE taskId IS NULL ORDER BY lastUpdated DESC")
    fun getAllConversations(): Flow<List<ChatConversation>>

    @Query("SELECT * FROM conversations WHERE taskId = :taskId ORDER BY lastUpdated DESC")
    fun getExecutionsForTask(taskId: String): Flow<List<ChatEntity>>

    @Query("SELECT * FROM conversations WHERE id = :conversationId")
    fun observeConversation(conversationId: String): Flow<ChatEntity?>

    @Query("SELECT * FROM new_chat_persist WHERE id = 0")
    fun observeNewChatPersist(): Flow<NewChatPersistEntity?>

    @Query("SELECT * FROM new_chat_persist WHERE id = 0")
    suspend fun getNewChatPersist(): NewChatPersistEntity?

    @Upsert
    suspend fun upsertNewChatPersist(entity: NewChatPersistEntity)

    @Query("DELETE FROM new_chat_persist WHERE id = 0")
    suspend fun deleteNewChatPersist(): Int

    @Query("SELECT * FROM conversation_settings_transfer WHERE conversationId = :conversationId")
    suspend fun getConversationSettingsTransfer(
        conversationId: String,
    ): ConversationSettingsTransferEntity?

    @Query("SELECT * FROM conversation_settings_transfer ORDER BY conversationId")
    suspend fun getPendingConversationSettingsTransfers(): List<ConversationSettingsTransferEntity>

    @Upsert
    suspend fun upsertConversationSettingsTransfer(entity: ConversationSettingsTransferEntity)

    @Query("DELETE FROM conversation_settings_transfer WHERE conversationId = :conversationId")
    suspend fun deleteConversationSettingsTransfer(conversationId: String): Int

    @Query("SELECT * FROM conversation_settings_import_transfer WHERE id = 0")
    suspend fun getConversationSettingsImportTransfer(): ConversationSettingsImportTransferEntity?

    @Upsert
    suspend fun upsertConversationSettingsImportTransfer(
        entity: ConversationSettingsImportTransferEntity,
    )

    @Query(
        """
        DELETE FROM conversation_settings_import_transfer
        WHERE id = 0 AND transferId = :transferId
        """
    )
    suspend fun deleteConversationSettingsImportTransfer(transferId: String): Int

    @Query("SELECT * FROM messages WHERE id = :messageId")
    fun observeMessage(messageId: String): Flow<MessageEntity?>

    @Query(
        """
        SELECT *
        FROM messages
        WHERE conversationId = :conversationId
          AND (
              text LIKE '%' || :escapedQuery || '%' ESCAPE '\'
              OR toolCallJson LIKE '%' || :escapedQuery || '%' ESCAPE '\'
          )
        ORDER BY timestamp ASC, id ASC
        """
    )
    fun observeConversationSearchMatches(
        conversationId: String,
        escapedQuery: String,
    ): Flow<List<MessageEntity>>

    @Query(
        """
        UPDATE messages
        SET status = 'STOPPED'
        WHERE conversationId = :conversationId
          AND status IN ('SENDING', 'THINKING', 'TOOL_CALLING', 'TRANSCRIBING')
        """
    )
    suspend fun stopStuckMessagesForConversation(conversationId: String): Int

    @Upsert
    suspend fun upsertConversation(conversation: ChatEntity)

    @Query("UPDATE conversations SET title = :title WHERE id = :conversationId")
    suspend fun updateConversationTitle(conversationId: String, title: String): Int

    @Query(
        """
        UPDATE conversations
        SET hasUnreadGeneration = :unread
        WHERE id = :conversationId AND hasUnreadGeneration != :unread
        """
    )
    suspend fun setConversationUnreadGeneration(
        conversationId: String,
        unread: Boolean,
    ): Int

    @Query("UPDATE conversations SET modelId = :newModelId WHERE modelId = :oldModelId")
    suspend fun replaceConversationModelReferences(
        oldModelId: String,
        newModelId: String?,
    ): Int

    @Query("UPDATE new_chat_persist SET modelId = :newModelId WHERE id = 0 AND modelId = :oldModelId")
    suspend fun replaceNewChatModelReference(
        oldModelId: String,
        newModelId: String?,
    ): Int

    @Query(
        """
        UPDATE conversations
        SET title = :newTitle
        WHERE id = :conversationId AND title = :expectedTitle
        """
    )
    suspend fun updateConversationTitleIfUnchanged(
        conversationId: String,
        expectedTitle: String,
        newTitle: String,
    ): Int

    @Upsert
    suspend fun upsertMessage(message: MessageEntity)

    // Runs
    @Upsert
    suspend fun upsertRun(run: RunEntity)

    @Query("SELECT * FROM runs WHERE conversationId = :conversationId ORDER BY startedAt, id")
    fun getRunsForConversation(conversationId: String): Flow<List<RunEntity>>

    @Query("SELECT * FROM runs WHERE conversationId = :conversationId ORDER BY startedAt, id")
    suspend fun getRunsForConversationSnapshot(conversationId: String): List<RunEntity>

    @Query(
        """
        UPDATE conversations
        SET selectedBranchesJson = :selectedBranchesJson,
            selectedRunBranchesJson = :selectedRunBranchesJson,
            modelId = :modelId,
            lastUpdated = :at
        WHERE id = :conversationId
        """
    )
    suspend fun updateConversationForRunAdmission(
        conversationId: String,
        selectedBranchesJson: String,
        selectedRunBranchesJson: String,
        modelId: String,
        at: Long,
    ): Int

    /**
     * Atomically removes one structural message subtree and only those Runs that become wholly
     * empty. [rootRunIdsToDelete] must be CASCADE-safe roots planned from the same locked
     * snapshot; a partially retained Run continues to own its shared boundary USER.
     * Attachment files are intentionally deleted only after this transaction commits.
     */
    @Transaction
    suspend fun deleteMessageSubtree(
        conversationId: String,
        rootMessageId: String,
        staleMessageIds: List<String>,
        rootRunIdsToDelete: List<String>,
        selectedBranchesJson: String,
        selectedRunBranchesJson: String,
        at: Long,
    ): Boolean {
        val root = getMessage(rootMessageId) ?: return false
        require(root.conversationId == conversationId) {
            "Message $rootMessageId does not belong to conversation $conversationId"
        }
        require(rootMessageId in staleMessageIds)
        if (staleMessageIds.isNotEmpty()) {
            deleteEmbeddingsByMessageIds(staleMessageIds)
        }
        check(
            updateSelectionsForRunDeletion(
                conversationId,
                selectedBranchesJson,
                selectedRunBranchesJson,
                at,
            ) == 1
        ) { "Conversation $conversationId disappeared during branch deletion" }
        for (runId in rootRunIdsToDelete) {
            val run = getRun(runId) ?: continue
            require(run.conversationId == conversationId) {
                "Run $runId does not belong to conversation $conversationId"
            }
            check(deleteRun(runId) == 1) { "Run $runId disappeared during deletion" }
        }
        deleteMessagesByIds(staleMessageIds)
        return true
    }

    @Transaction
    suspend fun createRunWithMessages(
        run: RunEntity,
        messages: List<MessageEntity>,
        messageSelectionUpdates: Map<String?, String>,
        conversationModelId: String,
        at: Long,
    ): RunGraphCommit {
        require(run.status == RunStatus.ACTIVE)
        require(run.activeSlot == 1)
        require(messages.isNotEmpty())
        require(conversationModelId.isNotBlank())
        require(messages.all { it.runId == run.id })
        require(messages.map { it.runSequence } == messages.indices.map { it.toLong() })
        val conversation = checkNotNull(getConversation(run.conversationId)) {
            "Conversation ${run.conversationId} does not exist"
        }
        check(getLiveRun(run.conversationId) == null) {
            "Conversation ${run.conversationId} already has a live Run"
        }
        val insertedMessageIds = messages.mapTo(mutableSetOf()) { it.id }
        require(messageSelectionUpdates.values.all { it in insertedMessageIds }) {
            "A new Run may only select messages committed in the same transaction"
        }
        insertRun(run)
        messages.forEach { insertMessage(it) }

        val messageSelections = decodeSelectionMap(conversation.selectedBranchesJson).apply {
            putAll(messageSelectionUpdates)
        }
        val runSelections = decodeSelectionMap(conversation.selectedRunBranchesJson).apply {
            put(run.parentRunId, run.id)
        }
        check(
            updateConversationForRunAdmission(
                conversationId = run.conversationId,
                selectedBranchesJson = encodeSelectionMap(messageSelections),
                selectedRunBranchesJson = encodeSelectionMap(runSelections),
                modelId = conversationModelId,
                at = at,
            ) == 1
        ) { "Conversation ${run.conversationId} disappeared during Run creation" }
        return RunGraphCommit(messages, messageSelections, runSelections)
    }

    /**
     * First Send in a new chat is one durable acceptance boundary. A failed Run/message insert
     * must not leave an empty conversation row behind.
     */
    @Transaction
    suspend fun createConversationRunWithMessages(
        conversation: ChatEntity,
        run: RunEntity,
        messages: List<MessageEntity>,
        messageSelectionUpdates: Map<String?, String>,
        conversationModelId: String,
        conversationSettingsJson: String?,
        at: Long,
    ): RunGraphCommit {
        require(conversation.id == run.conversationId)
        check(getConversation(conversation.id) == null) {
            "Conversation ${conversation.id} already exists"
        }
        require(conversationModelId.isNotBlank())
        deleteNewChatPersist()
        upsertConversation(conversation.copy(modelId = conversationModelId))
        upsertConversationSettingsTransfer(
            ConversationSettingsTransferEntity(
                conversationId = conversation.id,
                settingsJson = conversationSettingsJson,
            )
        )
        return createRunWithMessages(
            run,
            messages,
            messageSelectionUpdates,
            conversationModelId,
            at,
        )
    }

    @Transaction
    suspend fun importRunGraph(runs: List<RunEntity>, messages: List<MessageEntity>) {
        require(runs.all { it.status.isTerminal }) {
            "Imported Runs must be terminal"
        }
        val incomingRunIds = runs.mapTo(mutableSetOf()) { it.id }
        require(messages.all { it.runId in incomingRunIds || getRun(it.runId) != null }) {
            "Every imported message must reference an imported or existing Run"
        }
        for (run in runs) {
            if (getRun(run.id) == null) insertRun(run)
        }
        messages.forEach { upsertMessage(it) }
    }
    @Transaction
    suspend fun replaceImportedConversationGraph(
        conversations: List<ChatEntity>,
        runs: List<RunEntity>,
        messages: List<MessageEntity>,
    ) {
        val conversationIds = conversations.mapTo(mutableSetOf(), ChatEntity::id)
        val runIds = runs.mapTo(mutableSetOf(), RunEntity::id)
        require(conversationIds.size == conversations.size) { "Imported conversation IDs must be unique" }
        require(runIds.size == runs.size) { "Imported Run IDs must be unique" }
        require(messages.mapTo(mutableSetOf(), MessageEntity::id).size == messages.size) {
            "Imported message IDs must be unique"
        }
        val runsById = runs.associateBy(RunEntity::id)
        val messagesById = messages.associateBy(MessageEntity::id)
        require(runs.all {
            it.status.isTerminal &&
                it.activeSlot == null &&
                it.conversationId in conversationIds
        }) {
            "Every imported Run must be terminal and belong to an imported conversation"
        }
        require(runs.all { run ->
            run.parentRunId == null ||
                runsById[run.parentRunId]?.conversationId == run.conversationId
        }) {
            "Every imported parent Run must belong to the same replacement conversation"
        }
        require(messages.all { message ->
            message.conversationId in conversationIds &&
                runsById[message.runId]?.conversationId == message.conversationId &&
                (message.parentId == null ||
                    messagesById[message.parentId]?.conversationId == message.conversationId)
        }) {
            "Every imported message and parent must belong to its replacement conversation"
        }
        deleteAllConversations()
        conversations.forEach { upsertConversation(it) }
        runs.forEach { insertRun(it) }
        messages.forEach { upsertMessage(it) }
    }

    /**
     * Creates a fork as one database commit. A cancelled or rejected import must never leave
     * an empty conversation behind.
     */
    @Transaction
    suspend fun createForkGraph(
        conversation: ChatEntity,
        runs: List<RunEntity>,
        messages: List<MessageEntity>,
        sourceToForkMessageIds: Map<String, String>,
    ) {
        require(getConversation(conversation.id) == null) {
            "Fork conversation ${conversation.id} already exists"
        }
        require(runs.isNotEmpty())
        require(messages.isNotEmpty())
        require(runs.all { it.conversationId == conversation.id })
        require(messages.all { it.conversationId == conversation.id })
        val runIds = runs.mapTo(mutableSetOf()) { it.id }
        val messageIds = messages.mapTo(mutableSetOf()) { it.id }
        require(runs.all { it.parentRunId == null || it.parentRunId in runIds }) {
            "Every forked Run must reference another forked Run"
        }
        require(messages.all { it.parentId == null || it.parentId in messageIds }) {
            "Every forked message must reference another forked message"
        }
        require(messages.all { it.runId in runIds }) {
            "Every forked message must reference a forked Run"
        }
        require(sourceToForkMessageIds.size == messages.size)
        require(sourceToForkMessageIds.values.toSet() == messageIds) {
            "Every forked message must have exactly one source message"
        }
        require(sourceToForkMessageIds.keys.intersect(messageIds).isEmpty()) {
            "Forked messages must not reuse source message identities"
        }
        val clonedEmbeddings = ForkEmbeddingClonePolicy.cloneAll(
            sourceEmbeddings = getEmbeddingsByMessageIds(sourceToForkMessageIds.keys.toList()),
            sourceToForkMessageIds = sourceToForkMessageIds,
        )
        upsertConversation(conversation)
        importRunGraph(runs, messages)
        if (clonedEmbeddings.isNotEmpty()) {
            val insertedIds = insertEmbeddings(clonedEmbeddings)
            check(insertedIds.size == clonedEmbeddings.size && insertedIds.all { it > 0L }) {
                "Every forked embedding must be inserted as a new database row"
            }
        }
    }

    /** A provider tool round is protocol-atomic: assistant tool_calls and every result commit
     * together, or none of them do. */
    @Transaction
    suspend fun appendToolRoundToRun(
        messages: List<MessageEntity>,
        expectedPass: Int,
    ): ToolRoundCommit {
        require(expectedPass >= 0)
        val runId = ToolRoundCommitPolicy.requireValidShape(messages)
        ToolRoundCommitPolicy.resolveExactReplay(
            proposed = messages,
            // Avoid SQLite's bound-parameter ceiling for a malformed/extreme provider batch.
            existing = messages.mapNotNull { message -> getMessage(message.id) },
        )?.let { existing ->
            return ToolRoundCommit(existing, inserted = false)
        }
        val run = getRun(runId) ?: error("Run $runId does not exist")
        check(ToolRoundCommitPolicy.canInsert(run, runId, expectedPass)) {
            "Cannot append tool round to non-current Run $runId Pass $expectedPass"
        }
        val firstSequence = nextRunSequence(runId)
        val assigned = messages.mapIndexed { index, message ->
            message.copy(runSequence = firstSequence + index)
        }
        assigned.forEach { insertMessage(it) }
        touchRun(runId, maxOf(run.lastCheckpointAt, assigned.maxOf { it.timestamp }))
        return ToolRoundCommit(assigned, inserted = true)
    }

    @Query(
        """
        UPDATE runs
        SET status = 'STOPPING', stopRequestedAt = :at, lastCheckpointAt = :at
        WHERE id = :runId AND status = 'ACTIVE' AND activeSlot = 1
        """
    )
    suspend fun markRunStopping(runId: String, at: Long): Int

    @Query(
        """
        UPDATE runs
        SET status = :status, activeSlot = NULL, lastCheckpointAt = :at, endedAt = :at,
            endReason = :reason
        WHERE id = :runId AND activeSlot = 1
        """
    )
    suspend fun terminalizeLiveRun(
        runId: String,
        status: RunStatus,
        reason: RunEndReason,
        at: Long,
    ): Int

    @Query(
        """
        UPDATE messages
        SET status = 'STOPPED'
        WHERE runId = :runId
          AND participant = 'MODEL'
          AND status IN ('SENDING', 'THINKING', 'TOOL_CALLING', 'TRANSCRIBING')
        """
    )
    suspend fun stopInFlightModelMessages(runId: String): Int

    /**
     * Normal/error provider completion is one durable boundary: the model row and its Run become
     * terminal together. The update counts are returned as a boolean for diagnostics, but a
     * missing row never leaves an otherwise-live Run stranded.
     */
    @Transaction
    suspend fun finishGeneration(
        checkpoint: MessageStreamCheckpoint,
        conversationId: String,
        runId: String,
        status: RunStatus,
        reason: RunEndReason,
        at: Long,
        markConversationUnread: Boolean,
    ): Boolean {
        require(status.isTerminal)
        val messageUpdated = updateMessageCheckpoint(checkpoint) == 1
        val runUpdated = terminalizeLiveRun(runId, status, reason, at) == 1
        val completed = messageUpdated && runUpdated
        if (completed && markConversationUnread) {
            setConversationUnreadGeneration(conversationId, true)
        }
        return completed
    }

    /**
     * The only user-Stop terminal writer. Checkpoints and Run terminalization commit together, so
     * tree operations never observe a STOPPED message inside a still-live Run (or the inverse).
     */
    @Transaction
    suspend fun finishStoppedGeneration(
        checkpoints: List<MessageStreamCheckpoint>,
        runId: String?,
        at: Long,
    ): Boolean {
        checkpoints.forEach { updateMessageCheckpoint(it) }
        if (runId != null) stopInFlightModelMessages(runId)
        return runId == null || terminalizeLiveRun(
            runId,
            RunStatus.STOPPED,
            RunEndReason.USER_STOPPED,
            at,
        ) == 1
    }

    @Query(
        """
        SELECT * FROM runs
        WHERE activeSlot = 1 AND status IN ('ACTIVE', 'STOPPING')
        ORDER BY conversationId, id
        """
    )
    suspend fun getOrphanedLiveRuns(): List<RunEntity>

    @Transaction
    suspend fun recoverOrphanedRuns(at: Long): Int {
        val orphanedRuns = getOrphanedLiveRuns()
        if (orphanedRuns.isEmpty()) return 0
        val messagesByRun = getMessagesForRuns(orphanedRuns.map { it.id })
            .groupBy { it.runId }
        orphanedRuns.forEach { run ->
            val snapshot = RunRecoverySnapshot(
                conversationId = run.conversationId,
                runId = run.id,
                pass = run.currentPass,
                status = run.status,
            )
            val requested = ConversationRuntimeReducer.reduce(
                RunState.Idle(run.conversationId),
                ConversationCommand.Recover(snapshot),
            )
            val recoveryEffect = requested.effects
                .filterIsInstance<RunEffect.RecoverDurableRun>()
                .single()
            check(recoveryEffect.priorStatus == run.status)
            messagesByRun[run.id].orEmpty().forEach { message ->
                val recoveredStatus = RunRecoveryPolicy.recoverMessageStatus(
                    message.participant,
                    message.status,
                )
                val recoveredToolJson = message.toolCallJson?.let { raw ->
                    RunRecoveryPolicy.stopIncompleteToolsJson(raw) ?: raw
                }
                if (recoveredStatus != message.status || recoveredToolJson != message.toolCallJson) {
                    updateMessageCheckpoint(
                        MessageStreamCheckpoint(
                            id = message.id,
                            text = message.text,
                            images = message.images,
                            thoughts = message.thoughts,
                            thoughtTitle = message.thoughtTitle,
                            tokenCount = message.tokenCount,
                            inputTokenCount = message.inputTokenCount,
                            cachedInputTokenCount = message.cachedInputTokenCount,
                            cacheWriteInputTokenCount = message.cacheWriteInputTokenCount,
                            uncachedInputTokenCount = message.uncachedInputTokenCount,
                            outputTokenCount = message.outputTokenCount,
                            reasoningTokenCount = message.reasoningTokenCount,
                            status = recoveredStatus,
                            thoughtTimeMs = message.thoughtTimeMs,
                            toolCallJson = recoveredToolJson,
                        )
                    )
                }
            }
            val durableSuccess = terminalizeLiveRun(
                runId = recoveryEffect.identity.runId,
                status = RunStatus.STOPPED,
                reason = RunEndReason.PROCESS_RECOVERED,
                at = at,
            ) == 1
            val completed = ConversationRuntimeReducer.reduce(
                requested.newState,
                ConversationCommand.RecoveryCompleted(
                    recoveryEffect.identity,
                    durableSuccess,
                ),
            )
            check(durableSuccess && completed.accepted && completed.newState is RunState.Idle) {
                "Run recovery transaction lost ownership for ${run.id}"
            }
        }
        return orphanedRuns.size
    }

    @Update(entity = MessageEntity::class)
    suspend fun updateMessageCheckpoint(checkpoint: MessageStreamCheckpoint): Int

    @Query("DELETE FROM conversations WHERE id = :conversationId")
    suspend fun deleteConversation(conversationId: String)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteMessagesByConversation(conversationId: String)

    @Query("UPDATE runs SET parentRunId = :newParentRunId WHERE id = :runId")
    suspend fun updateRunParent(runId: String, newParentRunId: String?): Int

    @Query("DELETE FROM embeddings WHERE messageId IN (SELECT id FROM messages WHERE conversationId = :conversationId)")
    suspend fun deleteEmbeddingsByConversation(conversationId: String)

    @Query("DELETE FROM embeddings WHERE messageId LIKE 'compact_%' OR NOT EXISTS (SELECT 1 FROM messages WHERE messages.id = embeddings.messageId)")
    suspend fun deleteOrphanedEmbeddings()

    /** [query] must be pre-escaped for LIKE (see ConversationRepository.escapeLikePattern). */
    @Query("SELECT m.* FROM messages m INNER JOIN conversations c ON m.conversationId = c.id WHERE c.taskId IS NULL AND (m.text LIKE '%' || :query || '%' ESCAPE '\\' OR c.title LIKE '%' || :query || '%' ESCAPE '\\') AND m.participant IN ('USER', 'MODEL') AND m.text != '' AND substr(m.id, 1, 5) != 'tool_' AND substr(m.id, 1, 7) != 'result_' ORDER BY m.timestamp DESC, m.id DESC LIMIT :limit")
    suspend fun searchMessages(query: String, limit: Int = 10): List<MessageEntity>

    @Query("SELECT m.* FROM messages m INNER JOIN conversations c ON m.conversationId = c.id WHERE c.taskId IS NULL AND m.toolCallJson LIKE '%\"type\":\"citation\"%' AND m.participant = 'MODEL' AND substr(m.id, 1, 5) != 'tool_' AND substr(m.id, 1, 7) != 'result_' AND m.id > :afterId ORDER BY m.id ASC LIMIT :pageSize")
    suspend fun getMessagesWithCitationSegmentsPage(
        afterId: String,
        pageSize: Int,
    ): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastMessageForConversation(conversationId: String): MessageEntity?

    /** Message invalidations for task execution summaries. Unlike getExecutionsForTask(),
     * this Flow observes the messages table, so terminal status/snippet changes are emitted. */
    @Query("SELECT m.* FROM messages m INNER JOIN conversations c ON m.conversationId = c.id WHERE c.taskId = :taskId ORDER BY m.timestamp ASC")
    fun observeExecutionMessagesForTask(taskId: String): Flow<List<MessageEntity>>

    // Embeddings
    @Insert
    suspend fun insertEmbeddings(embeddings: List<EmbeddingEntity>): LongArray

    @Upsert
    suspend fun upsertEmbedding(embedding: EmbeddingEntity)

    @Query("SELECT * FROM embeddings WHERE messageId IN (:messageIds)")
    suspend fun getEmbeddingsByMessageIds(messageIds: List<String>): List<EmbeddingEntity>

    @Query("SELECT * FROM embeddings WHERE messageId = :messageId LIMIT 1")
    suspend fun getEmbedding(messageId: String): EmbeddingEntity?

    @Query("SELECT * FROM embeddings")
    suspend fun getAllEmbeddings(): List<EmbeddingEntity>

    @Query("DELETE FROM embeddings WHERE messageId = :messageId")
    suspend fun deleteEmbedding(messageId: String)

    @Query(
        """
        SELECT e.id, e.messageId, e.embedding, e.dimension
        FROM embeddings e
        CROSS JOIN messages m
        CROSS JOIN conversations c
        WHERE e.messageId = m.id
          AND m.conversationId = c.id
          AND e.modelId = :modelId
          AND e.id > :afterId
          AND c.taskId IS NULL
          AND m.participant IN ('USER', 'MODEL')
          AND LENGTH(m.text) >= :minimumTextLength
          AND m.id NOT LIKE 'tool_%'
          AND m.id NOT LIKE 'result_%'
          AND m.id NOT LIKE 'compact_%'
        ORDER BY e.id
        LIMIT :limit
        """
    )
    suspend fun getEmbeddingSearchPage(
        modelId: String,
        afterId: Long,
        minimumTextLength: Int,
        limit: Int,
    ): List<EmbeddingSearchRow>

    @Query("DELETE FROM embeddings WHERE modelId = :modelId")
    suspend fun deleteEmbeddingsByModel(modelId: String)

    @Query("SELECT COUNT(*) FROM embeddings e INNER JOIN messages m ON e.messageId = m.id INNER JOIN conversations c ON m.conversationId = c.id WHERE e.modelId = :modelId AND c.taskId IS NULL AND m.participant IN ('USER', 'MODEL') AND m.text != '' AND m.id NOT LIKE 'tool_%' AND m.id NOT LIKE 'result_%' AND m.id NOT LIKE 'compact_%'")
    suspend fun getEmbeddingCountByModel(modelId: String): Int

    @Query(
        """
        SELECT e.modelId AS modelId, COUNT(*) AS count
        FROM embeddings e
        INNER JOIN messages m ON e.messageId = m.id
        INNER JOIN conversations c ON m.conversationId = c.id
        WHERE e.modelId IN (:modelIds)
          AND c.taskId IS NULL
          AND m.participant IN ('USER', 'MODEL')
          AND m.text != ''
          AND m.id NOT LIKE 'tool_%'
          AND m.id NOT LIKE 'result_%'
          AND m.id NOT LIKE 'compact_%'
        GROUP BY e.modelId
        """
    )
    suspend fun getEmbeddingCountsByModels(modelIds: List<String>): List<EmbeddingModelCount>

    @Query("SELECT COUNT(*) FROM messages m INNER JOIN conversations c ON m.conversationId = c.id WHERE c.taskId IS NULL AND m.participant IN ('USER', 'MODEL') AND m.text != '' AND m.id NOT LIKE 'tool_%' AND m.id NOT LIKE 'result_%' AND m.id NOT LIKE 'compact_%'")
    suspend fun getIndexableMessageCount(): Int

    @Query(
        """
        SELECT m.id, m.text
        FROM messages m
        INNER JOIN conversations c ON m.conversationId = c.id
        WHERE c.taskId IS NULL
          AND m.participant IN ('USER', 'MODEL')
          AND m.text != ''
          AND m.id NOT LIKE 'tool_%'
          AND m.id NOT LIKE 'result_%' AND m.id NOT LIKE 'compact_%'
          AND NOT EXISTS (
              SELECT 1 FROM embeddings e
              WHERE e.messageId = m.id AND e.modelId = :modelId
          )
          AND (:afterId IS NULL OR m.id > :afterId)
        ORDER BY m.id
        LIMIT :limit
        """
    )
    suspend fun getUnembeddedMessagesPage(
        modelId: String,
        afterId: String?,
        limit: Int,
    ): List<IndexableMessage>

    @Query("SELECT * FROM messages WHERE id IN (:ids)")
    suspend fun getMessagesByIds(ids: List<String>): List<MessageEntity>

    @Query("SELECT m.* FROM messages m INNER JOIN conversations c ON m.conversationId = c.id WHERE m.id IN (:ids) AND c.taskId IS NULL AND m.participant IN ('USER', 'MODEL') AND m.text != '' AND m.id NOT LIKE 'tool_%' AND m.id NOT LIKE 'result_%' AND m.id NOT LIKE 'compact_%'")
    suspend fun getSearchableMessagesByIds(ids: List<String>): List<MessageEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM messages m INNER JOIN conversations c ON m.conversationId = c.id WHERE m.id = :messageId AND c.taskId IS NULL AND m.participant IN ('USER', 'MODEL') AND m.text != '' AND m.id NOT LIKE 'tool_%' AND m.id NOT LIKE 'result_%' AND m.id NOT LIKE 'compact_%')")
    suspend fun isMessageSearchable(messageId: String): Boolean

    /** Atomically enforces the search-visibility invariant for incremental indexing. */
    @Transaction
    suspend fun upsertEmbeddingIfSearchable(embedding: EmbeddingEntity): Boolean {
        if (!isMessageSearchable(embedding.messageId)) {
            deleteEmbedding(embedding.messageId)
            return false
        }
        upsertEmbedding(embedding)
        return true
    }

    @Query("SELECT * FROM conversations WHERE id = :conversationId AND taskId IS NULL")
    suspend fun getSearchableConversation(conversationId: String): ChatEntity?


    @Query("SELECT COUNT(*) FROM conversations WHERE taskId IS NULL")
    suspend fun getSearchableConversationCount(): Int

    @Query(
        """
        SELECT *
        FROM conversations
        WHERE taskId IS NULL
        ORDER BY lastUpdated ASC, id ASC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun getSearchableConversationsPageAscending(
        offset: Int,
        limit: Int,
    ): List<ChatEntity>

    @Query(
        """
        SELECT *
        FROM conversations
        WHERE taskId IS NULL
        ORDER BY lastUpdated DESC, id DESC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun getSearchableConversationsPageDescending(
        offset: Int,
        limit: Int,
    ): List<ChatEntity>

    @Query("UPDATE conversations SET draftText = :text, draftAttachments = :attachments WHERE id = :id")
    suspend fun updateDraft(id: String, text: String, attachments: String?)

    // Bulk export/import
    @Query("SELECT * FROM conversations")
    suspend fun getAllConversationsList(): List<ChatEntity>

    /** Repairs derived Run-selection metadata without making the conversation look newly edited. */
    @Query(
        """
        UPDATE conversations
        SET selectedRunBranchesJson = :replacement
        WHERE id = :conversationId AND selectedRunBranchesJson = :expected
        """
    )
    suspend fun compareAndSetRunBranchSelections(
        conversationId: String,
        expected: String,
        replacement: String,
    ): Int

    @Query("SELECT id FROM conversations")
    suspend fun getAllConversationIds(): List<String>

    @Query("SELECT id FROM tasks")
    suspend fun getAllTaskIds(): List<String>

    @Query(
        """
        SELECT *
        FROM messages
        WHERE (:afterId IS NULL OR id > :afterId)
        ORDER BY id
        LIMIT :limit
        """
    )
    suspend fun getMessagesPage(afterId: String?, limit: Int): List<MessageEntity>

    @Query(
        """
        SELECT id, images, attachmentMeta
        FROM messages
        WHERE (:afterId IS NULL OR id > :afterId)
          AND (
              (images != '' AND images != '[]')
              OR (attachmentMeta IS NOT NULL AND attachmentMeta != '')
          )
        ORDER BY id
        LIMIT :limit
        """
    )
    suspend fun getMessageAttachmentReferencesPage(
        afterId: String?,
        limit: Int,
    ): List<MessageAttachmentReference>

    @Query(
        """
        SELECT id, images, attachmentMeta
        FROM messages
        WHERE conversationId = :conversationId
          AND (:afterId IS NULL OR id > :afterId)
          AND (
              (images != '' AND images != '[]')
              OR (attachmentMeta IS NOT NULL AND attachmentMeta != '')
          )
        ORDER BY id
        LIMIT :limit
        """
    )
    suspend fun getConversationMessageAttachmentReferencesPage(
        conversationId: String,
        afterId: String?,
        limit: Int,
    ): List<MessageAttachmentReference>

    @Query(
        """
        SELECT id, toolCallJson
        FROM messages
        WHERE (:afterId IS NULL OR id > :afterId)
          AND toolCallJson IS NOT NULL
          AND toolCallJson != ''
        ORDER BY id
        LIMIT :limit
        """
    )
    suspend fun getMessageToolMediaReferencesPage(
        afterId: String?,
        limit: Int,
    ): List<MessageToolMediaReference>

    @Query(
        """
        SELECT id, draftAttachments
        FROM conversations
        WHERE (:afterId IS NULL OR id > :afterId)
          AND draftAttachments IS NOT NULL
          AND draftAttachments != ''
        ORDER BY id
        LIMIT :limit
        """
    )
    suspend fun getConversationDraftAttachmentReferencesPage(
        afterId: String?,
        limit: Int,
    ): List<ConversationDraftAttachmentReference>

    @Query(
        """
        SELECT draftAttachments
        FROM new_chat_persist
        WHERE id = 0
          AND draftAttachments IS NOT NULL
          AND draftAttachments != ''
        """
    )
    suspend fun getNewChatDraftAttachmentReference(): NewChatDraftAttachmentReference?

    @Query("DELETE FROM conversations")
    suspend fun deleteAllConversations()

    @Query("SELECT id FROM messages WHERE id IN (:ids)")
    suspend fun findExistingMessageIds(ids: List<String>): List<String>


    @Transaction
    suspend fun replaceConfiguredModelReferences(
        oldModelId: String,
        newModelId: String?,
    ) {
        replaceConversationModelReferences(oldModelId, newModelId)
        replaceNewChatModelReference(oldModelId, newModelId)
        replaceTaskModelReferences(oldModelId, newModelId)
    }

    @Query(
        """
        UPDATE conversations
        SET modelId = :newProvider || substr(modelId, length(:oldProvider) + 1)
        WHERE substr(modelId, 1, length(:oldProvider) + 1) = :oldProvider || ':'
        """
    )
    suspend fun renameConversationProviderModelReferences(
        oldProvider: String,
        newProvider: String,
    ): Int

    @Query(
        """
        UPDATE new_chat_persist
        SET modelId = :newProvider || substr(modelId, length(:oldProvider) + 1)
        WHERE id = 0
          AND substr(modelId, 1, length(:oldProvider) + 1) = :oldProvider || ':'
        """
    )
    suspend fun renameNewChatProviderModelReference(
        oldProvider: String,
        newProvider: String,
    ): Int

    @Query(
        """
        UPDATE messages
        SET modelName = :newProvider || substr(modelName, length(:oldProvider) + 1)
        WHERE substr(modelName, 1, length(:oldProvider) + 1) = :oldProvider || ':'
        """
    )
    suspend fun renameMessageProviderModelReferences(
        oldProvider: String,
        newProvider: String,
    ): Int

    @Transaction
    suspend fun renameConfiguredProviderModelReferences(oldProvider: String, newProvider: String) {
        renameConversationProviderModelReferences(oldProvider, newProvider)
        renameNewChatProviderModelReference(oldProvider, newProvider)
        renameTaskProviderModelReferences(oldProvider, newProvider)
        renameMessageProviderModelReferences(oldProvider, newProvider)
    }
}
