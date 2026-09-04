package com.newoether.agora.viewmodel

import android.content.Context
import androidx.work.WorkManager
import com.newoether.agora.R
import com.newoether.agora.api.ProviderDefaults
import com.newoether.agora.data.EmbeddingCacheLocks
import com.newoether.agora.data.EmbeddingModelConfig
import com.newoether.agora.data.EmbeddingModelType
import com.newoether.agora.data.local.SemanticIndexLedgerEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.service.EmbeddingCacheWorker
import com.newoether.agora.util.Constants
import com.newoether.agora.util.DebugLog
import com.newoether.agora.util.SnackbarEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

internal fun isEmbeddingMessageIdEligible(messageId: String): Boolean =
    !messageId.startsWith(Constants.COMPACT_MSG_PREFIX) &&
        !messageId.startsWith(Constants.TOOL_MSG_PREFIX) &&
        !messageId.startsWith(Constants.RESULT_MSG_PREFIX)

internal data class CacheWorkProgress(
    val processed: Int,
    val workTotal: Int,
    val cached: Int,
    val total: Int,
    val progressPermille: Int,
) {
    val fraction: Float
        get() = (progressPermille.coerceIn(0, 1000) / 1000f)
}

/**
 * Owns embedding-model settings, semantic-ledger admission, durable worker scheduling,
 * and the retained aggregate presentation used by Conversation Search settings.
 * Embedding generation belongs only to [EmbeddingCacheWorker] and the read-only RAG query path.
 */
class RagManager(
    private val conversations: ConversationRepository,
    private val settings: SettingsRepository,
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val emitSnackbar: suspend (SnackbarEvent) -> Unit,
) {
    val activeEmbeddingModel: StateFlow<EmbeddingModelConfig?> =
        combine(settings.embeddingModels, settings.activeEmbeddingModelId) { models, id ->
            models.find { it.id == id }
        }.stateIn(scope, SharingStarted.Eagerly, null)

    private val workManager = WorkManager.getInstance(appContext)
    private val _cachingModels = MutableStateFlow<Set<String>>(emptySet())
    val cachingModels: StateFlow<Set<String>> = _cachingModels.asStateFlow()
    private val _cacheWorkProgress =
        MutableStateFlow<Map<String, CacheWorkProgress>>(emptyMap())
    internal val cacheWorkProgress: StateFlow<Map<String, CacheWorkProgress>> =
        _cacheWorkProgress.asStateFlow()
    private val _cacheCounts = MutableStateFlow<Map<String, Pair<Int, Int>>>(emptyMap())
    val cacheCounts: StateFlow<Map<String, Pair<Int, Int>>> = _cacheCounts.asStateFlow()
    private val _cacheCountLoading = MutableStateFlow<Set<String>>(emptySet())
    val cacheCountLoading: StateFlow<Set<String>> = _cacheCountLoading.asStateFlow()
    private val _cacheCountFailures = MutableStateFlow<Set<String>>(emptySet())
    val cacheCountFailures: StateFlow<Set<String>> = _cacheCountFailures.asStateFlow()
    private val _ledgerStates = MutableStateFlow<Map<String, String>>(emptyMap())
    val ledgerStates: StateFlow<Map<String, String>> = _ledgerStates.asStateFlow()

    @Volatile private var cacheCountRefreshJob: Job? = null
    @Volatile private var pendingRefreshModels: List<EmbeddingModelConfig>? = null
    @Volatile private var cacheWorkObservationJob: Job? = null
    @Volatile private var observedModelIds: Set<String> = emptySet()
    @Volatile private var pendingReminderModelId: String? = null
    @Volatile private var postListStarted = false

    @Synchronized
    fun startPostList() {
        if (postListStarted) return
        postListStarted = true
        scope.launch(Dispatchers.IO) {
            settings.awaitInitialLoad()
            val activeId = settings.activeEmbeddingModelId.value
            if (settings.embeddingModels.value.any { it.id == activeId }) {
                admitActiveModel(activeId)
            }
        }
    }

    fun loadCacheCounts() {
        scope.launch(Dispatchers.IO) {
            settings.awaitInitialLoad()
            val models = settings.embeddingModels.value
            observeCacheWork(models.mapTo(linkedSetOf(), EmbeddingModelConfig::id))
            requestCacheCountRefresh(models = models)
            models.forEach { model ->
                runCatching {
                    EmbeddingCacheWorker.repairLegacyChain(model.id, workManager)
                }.onFailure { error ->
                    DebugLog.e(
                        "RagManager",
                        "Failed to repair legacy cache work for ${model.id}",
                        error,
                    )
                }
            }
        }
    }

    @Synchronized
    private fun requestCacheCountRefresh(
        reminderModelId: String? = null,
        models: List<EmbeddingModelConfig> = settings.embeddingModels.value,
    ) {
        if (reminderModelId != null) pendingReminderModelId = reminderModelId
        val modelIds = models.mapTo(linkedSetOf(), EmbeddingModelConfig::id)
        pruneRemovedModels(modelIds)
        if (models.isEmpty()) {
            pendingReminderModelId = null
            return
        }
        if (cacheCountRefreshJob?.isActive == true) {
            pendingRefreshModels = models
            return
        }
        lateinit var refreshJob: Job
        refreshJob = scope.launch(
            context = Dispatchers.IO,
            start = CoroutineStart.LAZY,
        ) {
            _cacheCountLoading.update { it + modelIds }
            try {
                refreshCachePresentation(models)
            } catch (error: Exception) {
                markCacheCountFailure(modelIds)
                clearPendingReminder(modelIds)
                DebugLog.e("RagManager", "Failed to refresh semantic cache presentation", error)
            } finally {
                _cacheCountLoading.update { it - modelIds }
                if (takePendingRefresh(refreshJob) != null) {
                    requestCacheCountRefresh()
                }
            }
        }
        cacheCountRefreshJob = refreshJob
        refreshJob.start()
    }

    private suspend fun refreshCachePresentation(models: List<EmbeddingModelConfig>) {
        val requestedModelIds = models.map(EmbeddingModelConfig::id).toSet()
        val configuredIds = settings.embeddingModels.value
            .mapTo(linkedSetOf(), EmbeddingModelConfig::id)
            .intersect(requestedModelIds)
        if (configuredIds.isEmpty()) {
            _cacheCounts.update { it - requestedModelIds }
            clearPendingReminder(requestedModelIds)
            return
        }
        val (total, cachedByModel, ledgers) = coroutineScope {
            val totalDeferred = async { conversations.getIndexableMessageCount() }
            val countsDeferred = async {
                conversations.getEmbeddingCountsByModels(configuredIds.toList())
                    .associate { it.modelId to it.count }
            }
            val ledgersDeferred = async {
                conversations.getSemanticLedgers(configuredIds.toList())
                    .associate { it.modelId to it.state }
            }
            Triple(totalDeferred.await(), countsDeferred.await(), ledgersDeferred.await())
        }
        val stillConfigured = settings.embeddingModels.value
            .mapTo(linkedSetOf(), EmbeddingModelConfig::id)
            .intersect(configuredIds)
        val counts = stillConfigured.associateWith { modelId ->
            (cachedByModel[modelId] ?: 0).coerceAtMost(total) to total
        }
        _cacheCounts.update { current -> (current - requestedModelIds) + counts }
        _ledgerStates.update { current -> (current - requestedModelIds) + ledgers }
        _cacheCountFailures.update { it - stillConfigured }
        takePendingReminder(requestedModelIds)?.let { modelId ->
            emitUncachedReminder(modelId, ledgers[modelId], counts[modelId])
        }
    }

    private fun markCacheCountFailure(modelIds: Set<String>) {
        val configuredIds = settings.embeddingModels.value
            .mapTo(linkedSetOf(), EmbeddingModelConfig::id)
        _cacheCountFailures.update { it + (modelIds intersect configuredIds) }
    }

    @Synchronized
    private fun takePendingRefresh(completedJob: Job): List<EmbeddingModelConfig>? {
        if (cacheCountRefreshJob !== completedJob) return null
        cacheCountRefreshJob = null
        return pendingRefreshModels.also { pendingRefreshModels = null }
    }

    @Synchronized
    private fun takePendingReminder(modelIds: Set<String>): String? {
        val modelId = pendingReminderModelId?.takeIf { it in modelIds } ?: return null
        pendingReminderModelId = null
        return modelId
    }

    @Synchronized
    private fun clearPendingReminder(modelIds: Set<String>) {
        if (pendingReminderModelId in modelIds) pendingReminderModelId = null
    }

    private fun pruneRemovedModels(modelIds: Set<String>) {
        _cacheCounts.update { counts -> counts.filterKeys { it in modelIds } }
        _cacheCountLoading.update { loading -> loading intersect modelIds }
        _cacheCountFailures.update { failures -> failures intersect modelIds }
        _ledgerStates.update { states -> states.filterKeys { it in modelIds } }
        _cachingModels.update { active -> active intersect modelIds }
        _cacheWorkProgress.update { progress -> progress.filterKeys { it in modelIds } }
    }

    @Synchronized
    private fun observeCacheWork(modelIds: Set<String>) {
        if (cacheWorkObservationJob?.isActive == true && observedModelIds == modelIds) return
        cacheWorkObservationJob?.cancel()
        observedModelIds = modelIds
        _cachingModels.update { it intersect modelIds }
        _cacheWorkProgress.update { it.filterKeys(modelIds::contains) }
        if (modelIds.isEmpty()) {
            cacheWorkObservationJob = null
            return
        }
        cacheWorkObservationJob = scope.launch(Dispatchers.IO) {
            coroutineScope {
                modelIds.forEach { modelId ->
                    launch {
                        var wasActive = false
                        var observedFinishedIds = emptySet<String>()
                        workManager.getWorkInfosForUniqueWorkFlow(
                            EmbeddingCacheWorker.workNameFor(modelId),
                        ).collect { infos ->
                            val unfinished = infos.filter { !it.state.isFinished }
                            val active = unfinished.isNotEmpty()
                            val finishedIds = infos.asSequence()
                                .filter { it.state.isFinished }
                                .mapTo(linkedSetOf()) { it.id.toString() }
                            val configured =
                                settings.embeddingModels.value.any { it.id == modelId }
                            _cachingModels.update { current ->
                                if (configured && active) current + modelId else current - modelId
                            }

                            val reportingWork = unfinished.firstOrNull {
                                it.state == androidx.work.WorkInfo.State.RUNNING
                            } ?: unfinished.firstOrNull()
                            val data = reportingWork?.progress
                            val progress = data
                                ?.takeIf {
                                    it.keyValueMap.containsKey(
                                        EmbeddingCacheWorker.KEY_PROGRESS_PERMILLE,
                                    )
                                }
                                ?.let {
                                    CacheWorkProgress(
                                        processed = it.getInt(
                                            EmbeddingCacheWorker.KEY_PROCESSED,
                                            0,
                                        ),
                                        workTotal = it.getInt(
                                            EmbeddingCacheWorker.KEY_WORK_TOTAL,
                                            0,
                                        ),
                                        cached = it.getInt(
                                            EmbeddingCacheWorker.KEY_CACHED,
                                            0,
                                        ),
                                        total = it.getInt(
                                            EmbeddingCacheWorker.KEY_TOTAL,
                                            0,
                                        ),
                                        progressPermille = it.getInt(
                                            EmbeddingCacheWorker.KEY_PROGRESS_PERMILLE,
                                            0,
                                        ),
                                    )
                                }
                            _cacheWorkProgress.update { current ->
                                if (configured && active && progress != null) {
                                    current + (modelId to progress)
                                } else {
                                    current - modelId
                                }
                            }
                            if (
                                configured && !active &&
                                infos.any { it.state == androidx.work.WorkInfo.State.FAILED }
                            ) {
                                _cacheCountFailures.update { it + modelId }
                            }
                            val refresh = configured && (
                                (wasActive && !active) ||
                                    finishedIds.any { it !in observedFinishedIds }
                                )
                            wasActive = configured && active
                            observedFinishedIds = finishedIds
                            if (refresh) requestCacheCountRefresh()
                        }
                    }
                }
            }
        }
    }

    private suspend fun emitUncachedReminder(
        modelId: String,
        ledgerState: String?,
        counts: Pair<Int, Int>?,
    ) {
        EmbeddingCacheLocks.forModel(modelId).withLock {
            if (settings.embeddingModels.value.none { it.id == modelId }) return@withLock
            if (ledgerState == null || ledgerState == SemanticIndexLedgerEntity.STATE_CURRENT) {
                return@withLock
            }
            if (settings.activeEmbeddingModelId.value != modelId) return@withLock
            if (settings.getAutoCacheEnabled() || !settings.getShowUncachedNotification()) {
                return@withLock
            }
            val notCached = counts?.let { (cached, total) -> (total - cached).coerceAtLeast(0) }
            val message = if (counts != null && notCached != null && notCached > 0) {
                appContext.getString(R.string.messages_not_cached, notCached, counts.second)
            } else {
                appContext.getString(R.string.not_cached)
            }
            emitSnackbar(
                SnackbarEvent(message, appContext.getString(R.string.cache_now)) {
                    cacheMessagesForModel(modelId)
                },
            )
        }
    }

    // -- Embedding-model CRUD ---------------------------------------------------------------

    fun addEmbeddingModel(config: EmbeddingModelConfig) {
        scope.launch(Dispatchers.IO) {
            settings.awaitInitialLoad()
            val wasEmpty = settings.embeddingModels.value.isEmpty()
            val models = settings.embeddingModels.value + config
            settings.saveEmbeddingModels(models)
            var added = false
            EmbeddingCacheLocks.forModel(config.id).withLock {
                if (settings.embeddingModels.value.any { it.id == config.id }) {
                    conversations.invalidateSemanticModel(config.id)
                    if (wasEmpty) settings.setActiveEmbeddingModelId(config.id)
                    added = true
                }
            }
            if (!added) return@launch
            if (wasEmpty) admitActiveModel(config.id)
            val currentModels = settings.embeddingModels.value
            observeCacheWork(currentModels.mapTo(linkedSetOf(), EmbeddingModelConfig::id))
            requestCacheCountRefresh(models = currentModels)
        }
    }

    fun deleteEmbeddingModel(id: String) {
        val workName = EmbeddingCacheWorker.workNameFor(id)
        workManager.cancelUniqueWork(workName)
        scope.launch(Dispatchers.IO) {
            withTimeoutOrNull(10_000) {
                workManager.getWorkInfosForUniqueWorkFlow(workName)
                    .first { infos -> infos.all { it.state.isFinished } }
            }
            var nextActiveModelId: String? = null
            val remainingModels = EmbeddingCacheLocks.forModel(id).withLock {
                val model = settings.embeddingModels.value.find { it.id == id }
                if (model?.type == EmbeddingModelType.LOCAL && model.localFilePath.isNotBlank()) {
                    java.io.File(model.localFilePath).delete()
                }
                conversations.deleteSemanticModel(id)
                val models = settings.embeddingModels.value.filter { it.id != id }
                settings.saveEmbeddingModels(models)
                if (settings.activeEmbeddingModelId.value == id && models.isNotEmpty()) {
                    nextActiveModelId = models.first().id
                    settings.setActiveEmbeddingModelId(models.first().id)
                }
                models
            }
            nextActiveModelId?.let { admitActiveModel(it) }
            observeCacheWork(remainingModels.mapTo(linkedSetOf(), EmbeddingModelConfig::id))
            requestCacheCountRefresh(models = remainingModels)
        }
    }

    fun renameEmbeddingModel(id: String, newName: String, batchSize: Int? = null) {
        scope.launch(Dispatchers.IO) {
            EmbeddingCacheLocks.forModel(id).withLock {
                val models = settings.embeddingModels.value.map {
                    if (it.id == id) it.copy(name = newName, batchSize = batchSize ?: it.batchSize) else it
                }
                settings.saveEmbeddingModels(models)
            }
        }
    }

    fun setActiveEmbeddingModel(id: String) {
        if (id == settings.activeEmbeddingModelId.value) return
        scope.launch(Dispatchers.IO) {
            EmbeddingCacheLocks.forModel(id).withLock {
                if (settings.embeddingModels.value.none { it.id == id }) return@withLock
                settings.setActiveEmbeddingModelId(id)
            }
            admitActiveModel(id)
        }
    }

    fun setAutoCacheEnabled(enabled: Boolean) {
        settings.setAutoCacheEnabled(enabled)
        if (!enabled) return
        scope.launch(Dispatchers.IO) {
            settings.awaitInitialLoad()
            val activeId = settings.activeEmbeddingModelId.value
            if (settings.embeddingModels.value.any { it.id == activeId }) {
                admitActiveModel(activeId, autoCacheOverride = true)
            }
        }
    }

    // -- Semantic ledger and durable cache work --------------------------------------------

    fun cacheMessagesForModel(modelId: String, recache: Boolean = false, silent: Boolean = false) {
        scope.launch(Dispatchers.IO) {
            settings.awaitInitialLoad()
            var model: EmbeddingModelConfig? = null
            var state: String? = null
            EmbeddingCacheLocks.forModel(modelId).withLock {
                val current = settings.embeddingModels.value.find { it.id == modelId }
                    ?: return@withLock
                model = current
                state = if (recache) {
                    conversations.invalidateSemanticModel(modelId)
                    conversations.getOrAdmitSemanticLedgerState(modelId)
                } else {
                    conversations.getOrAdmitSemanticLedgerState(modelId)
                }
                _ledgerStates.update { it + (modelId to checkNotNull(state)) }
                if (recache || state != SemanticIndexLedgerEntity.STATE_CURRENT) {
                    EmbeddingCacheWorker.schedule(modelId, workManager)
                }
            }
            val configuredModel = model ?: return@launch
            if (!recache && state == SemanticIndexLedgerEntity.STATE_CURRENT) return@launch
            val models = settings.embeddingModels.value
            observeCacheWork(models.mapTo(linkedSetOf(), EmbeddingModelConfig::id))
            requestCacheCountRefresh(models = models)
            if (!silent) {
                emitSnackbar(
                    SnackbarEvent(
                        appContext.getString(R.string.embedding_model_caching, configuredModel.name),
                    ),
                )
            }
        }
    }

    private suspend fun admitActiveModel(
        modelId: String,
        autoCacheOverride: Boolean? = null,
    ) {
        EmbeddingCacheLocks.forModel(modelId).withLock {
            if (settings.embeddingModels.value.none { it.id == modelId }) return@withLock
            val state = conversations.getOrAdmitSemanticLedgerState(modelId)
            _ledgerStates.update { it + (modelId to state) }
            if (state == SemanticIndexLedgerEntity.STATE_CURRENT) return@withLock
            if (autoCacheOverride ?: settings.getAutoCacheEnabled()) {
                EmbeddingCacheWorker.schedule(modelId, workManager)
            } else if (settings.getShowUncachedNotification()) {
                requestCacheCountRefresh(
                    reminderModelId = modelId,
                    models = settings.embeddingModels.value,
                )
            }
        }
    }

    /**
     * Searchable message persistence already enqueues exact ledger work transactionally.
     * This callback only wakes the one durable consumer when Auto Cache is enabled.
     */
    fun indexMessageForRag(messageId: String, @Suppress("UNUSED_PARAMETER") text: String) {
        if (!isEmbeddingMessageIdEligible(messageId) || !settings.autoCacheEnabled.value) return
        activeEmbeddingModel.value?.id?.let { modelId ->
            scope.launch(Dispatchers.IO) {
                if (
                    settings.autoCacheEnabled.value &&
                    settings.embeddingModels.value.any { it.id == modelId }
                ) {
                    // Enqueueing is a wake-up, not a cache mutation, and must never wait behind
                    // remote/JNI embedding work.
                    EmbeddingCacheWorker.schedule(modelId, workManager)
                }
            }
        }
    }

    // -- Embedding key / base-URL resolution ------------------------------------------------

    fun resolveEmbeddingApiKey(): String? {
        val keys = settings.apiKeys.value
        for (entry in keys) {
            if (ProviderDefaults.isOpenAiCompatibleEmbedding(entry.provider)) return entry.key
        }
        return keys.firstOrNull()?.key
    }

    fun resolveEmbeddingBaseUrl(): String =
        ProviderDefaults.openAiCompatibleBaseUrl(settings.providerBaseUrls.value)

    data class EmbeddingKeyInfo(val provider: String, val key: String, val baseUrl: String)

    /** Exact match only -- for UI display in the embedding dialog. No fallback. */
    fun resolveEmbeddingKeyForProviderExact(targetProvider: String): EmbeddingKeyInfo? {
        val match = settings.apiKeys.value.find {
            it.provider.equals(targetProvider, ignoreCase = true)
        } ?: return null
        val baseUrl = settings.providerBaseUrls.value[match.provider]
            ?: ProviderDefaults.embeddingBaseUrl(match.provider)
        return EmbeddingKeyInfo(match.provider, match.key, baseUrl)
    }
}
