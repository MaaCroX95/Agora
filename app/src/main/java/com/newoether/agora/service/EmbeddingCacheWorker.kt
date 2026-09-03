package com.newoether.agora.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.newoether.agora.AgoraApplication
import com.newoether.agora.api.EmbeddingClient
import com.newoether.agora.api.LlamaEngine
import com.newoether.agora.api.ProviderDefaults
import com.newoether.agora.data.EmbeddingIndexer
import com.newoether.agora.data.EmbeddingModelConfig
import com.newoether.agora.data.EmbeddingModelType
import com.newoether.agora.data.SettingsManager
import com.newoether.agora.data.local.ChatDatabase
import com.newoether.agora.data.local.EmbeddingEntity
import com.newoether.agora.data.local.IndexableMessage
import com.newoether.agora.data.local.SemanticIndexLedgerEntity
import com.newoether.agora.data.local.commitSemanticEmbedding
import com.newoether.agora.data.local.semanticSourceFingerprint
import com.newoether.agora.data.replaceCustomProviderIdsForDisplay
import com.newoether.agora.util.Constants
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/** The single durable consumer for one embedding model's semantic ledger work. */
class EmbeddingCacheWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val modelId = inputData.getString(KEY_MODEL_ID)
        if (modelId.isNullOrBlank()) {
            DebugLog.w(TAG, "No model_id in input data")
            return@withContext Result.failure()
        }
        val container = (applicationContext as AgoraApplication).awaitContainer()
            ?: return@withContext Result.retry()

        cacheModel(modelId, container.database, container.settingsManager)
    }

    internal suspend fun cacheModel(
        modelId: String,
        database: ChatDatabase,
        settingsManager: SettingsManager,
    ): Result {
        val model = settingsManager.embeddingModels.first().find { it.id == modelId }
            ?: return Result.success()
        val semanticDao = database.semanticIndexDao()
        val ledger = semanticDao.getLedger(modelId) ?: return Result.success()
        if (ledger.state == SemanticIndexLedgerEntity.STATE_CURRENT) return Result.success()

        return try {
            val completed = when (ledger.state) {
                SemanticIndexLedgerEntity.STATE_PENDING ->
                    consumeExactWork(model, settingsManager, database)

                SemanticIndexLedgerEntity.STATE_NEEDS_RECONCILE ->
                    reconcileModel(model, settingsManager, ledger.sourceRevision, database)

                else -> true
            }
            if (!completed) return Result.retry()
            val latest = semanticDao.getLedger(modelId)
            if (latest == null || latest.state == SemanticIndexLedgerEntity.STATE_CURRENT) {
                Result.success(workDataOf(KEY_FAILED to 0))
            } else {
                Result.retry()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            DebugLog.e(TAG, "Cache worker failed", error)
            val displayError = replaceCustomProviderIdsForDisplay(
                error.localizedMessage ?: "Unknown error",
                settingsManager.customProviders.first(),
            )
            Result.failure(workDataOf(KEY_ERROR to displayError))
        }
    }

    private suspend fun consumeExactWork(
        model: EmbeddingModelConfig,
        settingsManager: SettingsManager,
        database: ChatDatabase,
    ): Boolean {
        val semanticDao = database.semanticIndexDao()
        var afterRevision = 0L
        var afterMessageId = ""
        var embeddingConfigResolved = false
        var remoteConfig: Pair<String, String>? = null
        var complete = true
        while (true) {
            val page = semanticDao.getWorkPage(
                modelId = model.id,
                afterSourceRevision = afterRevision,
                afterMessageId = afterMessageId,
                limit = model.batchSize.coerceIn(1, MAX_BATCH_SIZE),
            )
            if (page.isEmpty()) break
            afterRevision = page.last().sourceRevision
            afterMessageId = page.last().messageId
            val candidates = mutableListOf<Triple<Long?, String, IndexableMessage>>()
            page.forEach { work ->
                if (work.sourceFingerprint == null) {
                    semanticDao.completeExactWork(work, System.currentTimeMillis())
                } else {
                    val text = semanticDao.getSearchableMessageText(work.messageId)
                    if (text != null && semanticSourceFingerprint(text) == work.sourceFingerprint) {
                        candidates += Triple(
                            work.sourceRevision,
                            work.sourceFingerprint,
                            IndexableMessage(work.messageId, text),
                        )
                    }
                }
            }
            if (candidates.isNotEmpty() && !embeddingConfigResolved) {
                remoteConfig = resolveEmbeddingConfig(model, settingsManager)
                embeddingConfigResolved = true
            }
            if (!embedCandidates(model, remoteConfig, candidates, database)) complete = false
            yield()
        }
        val latest = semanticDao.getLedger(model.id) ?: return true
        if (latest.state == SemanticIndexLedgerEntity.STATE_PENDING) {
            semanticDao.markCurrentAfterExactWork(
                modelId = model.id,
                sourceRevision = latest.sourceRevision,
                updatedAt = System.currentTimeMillis(),
            )
        }
        return complete
    }

    private suspend fun reconcileModel(
        model: EmbeddingModelConfig,
        settingsManager: SettingsManager,
        expectedRevision: Long,
        database: ChatDatabase,
    ): Boolean {
        val chatDao = database.chatDao()
        var afterMessageId: String? = null
        var embeddingConfigResolved = false
        var remoteConfig: Pair<String, String>? = null
        var complete = true
        while (true) {
            val page = chatDao.getSearchableMessagesPage(
                afterId = afterMessageId,
                limit = model.batchSize.coerceIn(1, MAX_BATCH_SIZE),
            )
            if (page.isEmpty()) break
            afterMessageId = page.last().id
            val candidates = page.map { message ->
                Triple(null, semanticSourceFingerprint(message.text), message)
            }
            if (candidates.isNotEmpty() && !embeddingConfigResolved) {
                remoteConfig = resolveEmbeddingConfig(model, settingsManager)
                embeddingConfigResolved = true
            }
            if (
                !embedCandidates(
                    model,
                    remoteConfig,
                    candidates,
                    database,
                    expectedLedgerRevision = expectedRevision,
                )
            ) complete = false
            yield()
        }
        if (!complete) return false
        return database.semanticIndexDao().completeReconcile(
            modelId = model.id,
            expectedRevision = expectedRevision,
            updatedAt = System.currentTimeMillis(),
        )
    }

    private suspend fun embedCandidates(
        model: EmbeddingModelConfig,
        remoteConfig: Pair<String, String>?,
        candidates: List<Triple<Long?, String, IndexableMessage>>,
        database: ChatDatabase,
        expectedLedgerRevision: Long? = null,
    ): Boolean {
        if (candidates.isEmpty()) return true
        val texts = candidates.map { (_, _, message) ->
            message.text.take(Constants.MAX_EMBEDDING_TEXT_LENGTH)
        }
        val embeddings = if (model.type == EmbeddingModelType.LOCAL) {
            LlamaEngine.computeEmbeddings(texts, model.localFilePath)
        } else {
            val (apiKey, baseUrl) = requireNotNull(remoteConfig)
            EmbeddingClient.computeEmbeddings(
                texts = texts,
                apiKey = apiKey,
                model = model.remoteModelName,
                baseUrl = baseUrl,
            )
        }
        var complete = embeddings.size == candidates.size
        candidates.forEachIndexed { index, (workRevision, fingerprint, message) ->
            val embedding = embeddings.getOrNull(index)
            if (embedding == null) {
                complete = false
            } else if (!database.commitSemanticEmbedding(
                    embedding = EmbeddingEntity(
                        messageId = message.id,
                        modelId = model.id,
                        embedding = EmbeddingIndexer.floatsToBytes(embedding),
                        chunkText = message.text.take(Constants.MAX_CHUNK_TEXT_LENGTH),
                        dimension = embedding.size,
                    ),
                    expectedFingerprint = fingerprint,
                    expectedWorkRevision = workRevision,
                    expectedLedgerRevision = expectedLedgerRevision,
                    completePendingWork = workRevision != null,
                    updatedAt = System.currentTimeMillis(),
                )
            ) {
                complete = false
            }
        }
        return complete
    }

    private suspend fun resolveEmbeddingConfig(
        model: EmbeddingModelConfig,
        settingsManager: SettingsManager,
    ): Pair<String, String>? {
        if (model.type == EmbeddingModelType.LOCAL) {
            check(LlamaEngine.isModelReady(model.localFilePath)) { "Local model file not found" }
            return null
        }
        val apiKey = model.remoteApiKey.ifBlank { resolveApiKey(settingsManager) ?: "" }
        check(apiKey.isNotBlank()) { "No API key configured" }
        return apiKey to model.remoteBaseUrl.ifBlank { resolveBaseUrl(settingsManager) }
    }

    private suspend fun resolveApiKey(settingsManager: SettingsManager): String? {
        val keys = settingsManager.apiKeys.first()
        return keys.firstOrNull { ProviderDefaults.isOpenAiCompatibleEmbedding(it.provider) }?.key
            ?: keys.firstOrNull()?.key
    }

    private suspend fun resolveBaseUrl(settingsManager: SettingsManager): String =
        ProviderDefaults.openAiCompatibleBaseUrl(settingsManager.providerBaseUrls.first())

    companion object {
        const val KEY_MODEL_ID = "model_id"
        const val KEY_CACHED = "cached"
        const val KEY_TOTAL = "total"
        const val KEY_FAILED = "failed"
        const val KEY_ERROR = "error"
        const val TAG = "EmbeddingCache"
        private const val MAX_BATCH_SIZE = 32

        fun workNameFor(modelId: String) = "embedding_cache_$modelId"

        fun schedule(modelId: String, workManager: WorkManager = WorkManager.getInstance()) {
            require(modelId.isNotBlank())
            val request = OneTimeWorkRequestBuilder<EmbeddingCacheWorker>()
                .setInputData(workDataOf(KEY_MODEL_ID to modelId))
                .addTag(TAG)
                .build()
            workManager.enqueueUniqueWork(
                workNameFor(modelId),
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        }
    }
}
