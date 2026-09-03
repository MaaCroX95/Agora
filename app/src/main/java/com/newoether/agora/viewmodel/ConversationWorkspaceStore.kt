package com.newoether.agora.viewmodel

import com.newoether.agora.data.ConversationSettings
import com.newoether.agora.data.local.NewChatPersistEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.ConversationSettingsTransferCoordinator
import com.newoether.agora.data.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal const val NEW_CHAT_WORKSPACE_ID = "agora:new-chat"

internal data class ConversationWorkspaceDraft(
    val text: String,
    val attachmentsJson: String?,
)

internal data class NewChatWorkspaceSnapshot(
    val rowExists: Boolean,
    val modelId: String?,
    val systemPromptId: String?,
    val conversationSettings: ConversationSettings?,
)

internal data class NewChatSendAdmission(
    val modelId: String,
    val systemPromptId: String?,
    val conversationSettings: ConversationSettings?,
)

internal fun NewChatWorkspaceSnapshot.toSendAdmission(
    globalDefaultModel: String,
): NewChatSendAdmission = NewChatSendAdmission(
    modelId = modelId ?: globalDefaultModel,
    systemPromptId = systemPromptId,
    conversationSettings = conversationSettings,
)

internal interface ComposerDraftPersistence {
    suspend fun loadDraft(ownerId: String): ConversationWorkspaceDraft

    suspend fun updateDraft(
        ownerId: String,
        text: String,
        attachmentsJson: String?,
    )

    suspend fun clearAcceptedDraft(ownerId: String)
}

/** Routes mutable conversation workspace state to Room or the New Chat singleton. */
internal class ConversationWorkspaceStore(
    private val conversations: ConversationRepository,
    private val settings: SettingsRepository,
    private val transfers: ConversationSettingsTransferCoordinator,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ComposerDraftPersistence {
    private sealed interface NewChatCommand {
        data class Update(
            val transform: (NewChatPersistEntity) -> NewChatPersistEntity,
            val completion: CompletableDeferred<Unit>?,
        ) : NewChatCommand

        data class Read(
            val completion: CompletableDeferred<NewChatPersistEntity?>,
        ) : NewChatCommand

        data class Clear(
            val completion: CompletableDeferred<Unit>,
        ) : NewChatCommand
    }

    private val newChatCommands = Channel<NewChatCommand>(Channel.UNLIMITED)
    private val newChatInitialLoad = CompletableDeferred<Unit>()
    private val newChatStateLock = Any()
    private var pendingNewChatWrites = 0
    private val _newChatPersist = MutableStateFlow<NewChatPersistEntity?>(null)
    private val conversationMutationMutex = Mutex()

    val newChatPersist: StateFlow<NewChatPersistEntity?> = _newChatPersist.asStateFlow()
    val newChatModelId: StateFlow<String?> = newChatPersist
        .map { it?.modelId }
        .stateIn(scope, SharingStarted.Eagerly, null)
    val newChatSystemPromptId: StateFlow<String?> = newChatPersist
        .map { it?.systemPromptId }
        .stateIn(scope, SharingStarted.Eagerly, null)
    val newChatConversationSettings: StateFlow<ConversationSettings?> = newChatPersist
        .map { entity -> decodeConversationSettings(entity?.conversationSettingsJson) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    init {
        scope.launch(ioDispatcher) {
            try {
                val initial = conversations.getNewChatPersist()
                synchronized(newChatStateLock) {
                    _newChatPersist.value = initial
                }
                newChatInitialLoad.complete(Unit)
                for (command in newChatCommands) {
                    processNewChatCommand(command)
                }
            } catch (cancelled: CancellationException) {
                newChatInitialLoad.completeExceptionally(cancelled)
                newChatCommands.close(cancelled)
                throw cancelled
            } catch (error: Exception) {
                newChatInitialLoad.completeExceptionally(error)
                newChatCommands.close(error)
            }
        }
        scope.launch(ioDispatcher) {
            newChatInitialLoad.await()
            conversations.observeNewChatPersist().collect { persisted ->
                synchronized(newChatStateLock) {
                    // First Send deletes the Room singleton inside its graph transaction. Keep the
                    // in-memory workspace alive until the committed conversation has inherited its
                    // state and the selection owner has been published explicitly.
                    if (pendingNewChatWrites == 0 && persisted != null) {
                        _newChatPersist.value = persisted
                    }
                }
            }
        }
    }

    fun setModel(ownerId: String, modelId: String?) {
        if (ownerId == NEW_CHAT_WORKSPACE_ID) {
            enqueueNewChatUpdate { it.copy(modelId = modelId) }
        } else {
            updateConversation(ownerId) { it.copy(modelId = modelId) }
        }
    }

    fun setSystemPrompt(ownerId: String, promptId: String?) {
        if (ownerId == NEW_CHAT_WORKSPACE_ID) {
            enqueueNewChatUpdate { it.copy(systemPromptId = promptId) }
        } else {
            updateConversation(ownerId) { it.copy(systemPromptId = promptId) }
        }
    }

    fun setConversationSettings(ownerId: String, value: ConversationSettings?) {
        if (ownerId == NEW_CHAT_WORKSPACE_ID) {
            enqueueNewChatUpdate {
                it.copy(
                    conversationSettingsJson = value?.let { settings ->
                        Json.encodeToString(settings)
                    },
                )
            }
        } else {
            settings.setConversationSettings(ownerId, value)
        }
    }

    fun updateConversationSettings(
        ownerId: String,
        transform: (ConversationSettings) -> ConversationSettings,
    ) {
        if (ownerId == NEW_CHAT_WORKSPACE_ID) {
            enqueueNewChatUpdate { entity ->
                val current = decodeConversationSettings(entity.conversationSettingsJson)
                    ?: ConversationSettings()
                entity.copy(conversationSettingsJson = Json.encodeToString(transform(current)))
            }
        } else {
            settings.updateConversationSettings(ownerId, transform)
        }
    }

    override suspend fun loadDraft(ownerId: String): ConversationWorkspaceDraft =
        if (ownerId == NEW_CHAT_WORKSPACE_ID) {
            val entity = readNewChatPersist()
            ConversationWorkspaceDraft(
                text = entity?.draftText.orEmpty(),
                attachmentsJson = entity?.draftAttachments,
            )
        } else {
            val entity = conversations.getConversation(ownerId)
            ConversationWorkspaceDraft(
                text = entity?.draftText.orEmpty(),
                attachmentsJson = entity?.draftAttachments,
            )
        }

    override suspend fun updateDraft(
        ownerId: String,
        text: String,
        attachmentsJson: String?,
    ) {
        if (ownerId == NEW_CHAT_WORKSPACE_ID) {
            updateNewChatAndAwait {
                it.copy(draftText = text, draftAttachments = attachmentsJson)
            }
        } else {
            withContext(Dispatchers.IO) {
                conversationMutationMutex.withLock {
                    conversations.updateDraft(ownerId, text, attachmentsJson)
                }
            }
        }
    }

    override suspend fun clearAcceptedDraft(ownerId: String) {
        if (ownerId == NEW_CHAT_WORKSPACE_ID) {
            clearNewChatAndAwait()
        } else {
            withContext(Dispatchers.IO) {
                conversationMutationMutex.withLock {
                    conversations.updateDraft(ownerId, "", null)
                }
            }
        }
    }

    suspend fun awaitNewChatWrites(): NewChatWorkspaceSnapshot {
        val entity = readNewChatPersist()
        return NewChatWorkspaceSnapshot(
            rowExists = entity != null,
            modelId = entity?.modelId,
            systemPromptId = entity?.systemPromptId,
            conversationSettings = decodeConversationSettings(entity?.conversationSettingsJson),
        )
    }

    suspend fun applyCommittedNewConversationState(conversationId: String) {
        transfers.complete(conversationId)
    }

    suspend fun clearCommittedNewChatWorkspace() {
        clearAcceptedDraft(NEW_CHAT_WORKSPACE_ID)
    }

    private fun updateConversation(
        conversationId: String,
        transform: (com.newoether.agora.data.local.ChatEntity) -> com.newoether.agora.data.local.ChatEntity,
    ) {
        scope.launch(ioDispatcher) {
            conversationMutationMutex.withLock {
                conversations.getConversation(conversationId)?.let { current ->
                    conversations.upsertConversation(transform(current))
                }
            }
        }
    }

    private fun enqueueNewChatUpdate(
        transform: (NewChatPersistEntity) -> NewChatPersistEntity,
    ) {
        synchronized(newChatStateLock) {
            pendingNewChatWrites += 1
        }
        if (newChatCommands.trySend(NewChatCommand.Update(transform, null)).isFailure) {
            synchronized(newChatStateLock) {
                pendingNewChatWrites -= 1
            }
        }
    }

    private suspend fun updateNewChatAndAwait(
        transform: (NewChatPersistEntity) -> NewChatPersistEntity,
    ) {
        val completion = CompletableDeferred<Unit>()
        synchronized(newChatStateLock) {
            pendingNewChatWrites += 1
        }
        try {
            newChatCommands.send(NewChatCommand.Update(transform, completion))
        } catch (error: Throwable) {
            synchronized(newChatStateLock) {
                pendingNewChatWrites -= 1
            }
            throw error
        }
        completion.await()
    }

    private suspend fun readNewChatPersist(): NewChatPersistEntity? {
        val completion = CompletableDeferred<NewChatPersistEntity?>()
        newChatCommands.send(NewChatCommand.Read(completion))
        return completion.await()
    }

    private suspend fun clearNewChatAndAwait() {
        val completion = CompletableDeferred<Unit>()
        synchronized(newChatStateLock) {
            pendingNewChatWrites += 1
        }
        try {
            newChatCommands.send(NewChatCommand.Clear(completion))
        } catch (error: Throwable) {
            synchronized(newChatStateLock) {
                pendingNewChatWrites -= 1
            }
            throw error
        }
        completion.await()
    }

    private suspend fun processNewChatCommand(command: NewChatCommand) {
        when (command) {
            is NewChatCommand.Read -> command.completion.complete(
                synchronized(newChatStateLock) { _newChatPersist.value },
            )

            is NewChatCommand.Update -> {
                try {
                    val next = synchronized(newChatStateLock) {
                        command.transform(_newChatPersist.value ?: NewChatPersistEntity())
                            .also { _newChatPersist.value = it }
                    }
                    conversations.upsertNewChatPersist(next)
                    command.completion?.complete(Unit)
                } catch (cancelled: CancellationException) {
                    command.completion?.completeExceptionally(cancelled)
                    throw cancelled
                } catch (error: Exception) {
                    restoreNewChatAfterFailure()
                    command.completion?.completeExceptionally(error)
                } finally {
                    synchronized(newChatStateLock) {
                        pendingNewChatWrites -= 1
                    }
                }
            }

            is NewChatCommand.Clear -> {
                try {
                    synchronized(newChatStateLock) {
                        _newChatPersist.value = null
                    }
                    conversations.deleteNewChatPersist()
                    command.completion.complete(Unit)
                } catch (cancelled: CancellationException) {
                    command.completion.completeExceptionally(cancelled)
                    throw cancelled
                } catch (error: Exception) {
                    restoreNewChatAfterFailure()
                    command.completion.completeExceptionally(error)
                } finally {
                    synchronized(newChatStateLock) {
                        pendingNewChatWrites -= 1
                    }
                }
            }
        }
    }

    private suspend fun restoreNewChatAfterFailure() {
        val persisted = runCatching { conversations.getNewChatPersist() }.getOrNull()
        synchronized(newChatStateLock) {
            _newChatPersist.value = persisted
        }
    }

    private companion object {
        fun decodeConversationSettings(raw: String?): ConversationSettings? = raw?.let {
            runCatching { Json.decodeFromString<ConversationSettings>(it) }.getOrNull()
        }
    }
}
