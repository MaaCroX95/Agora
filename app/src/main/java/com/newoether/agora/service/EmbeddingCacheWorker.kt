package com.newoether.agora.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.newoether.agora.AgoraApplication
import com.newoether.agora.api.EmbeddingClient
import com.newoether.agora.api.LlamaEngine
import com.newoether.agora.api.ProviderDefaults
import com.newoether.agora.data.EmbeddingCacheLocks
import com.newoether.agora.data.EmbeddingModelType
import com.newoether.agora.data.EmbeddingIndexer
import com.newoether.agora.data.SettingsManager
import com.newoether.agora.data.replaceCustomProviderIdsForDisplay
import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.EmbeddingEntity
import com.newoether.agora.util.Constants
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * WorkManager continuation for embedding caching — the runner that survives process
 * death. RagManager's in-app coroutine is the primary runner: it enqueues this worker
 * only after taking the model's [EmbeddingCacheLocks] lock and cancels it on every
 * in-process exit, so this worker computes anything only when the process died
 * mid-cache and WorkManager restarted it. Taking the same lock here makes concurrent
 * double-computation impossible even in edge orderings.
 *
 * Input data: "model_id" (String) — the embedding model ID to cache.
 * Output data: "cached" (Int), "total" (Int), "failed" (Int).
 */
class EmbeddingCacheWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_MODEL_ID = "model_id"
        const val KEY_CACHED = "cached"
        const val KEY_TOTAL = "total"
        const val KEY_FAILED = "failed"
        const val TAG = "EmbeddingCache"

        /** Enqueue rule: only one cache job per model at a time. */
        fun workNameFor(modelId: String) = "embedding_cache_$modelId"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val modelId = inputData.getString(KEY_MODEL_ID)
        if (modelId.isNullOrBlank()) {
            DebugLog.w(TAG, "No model_id in input data")
            return@withContext Result.failure()
        }

        // Container singletons, NOT fresh instances: a second Room instance on the same
        // file bypasses the app's invalidation tracker (UI Flows would go stale), and a
        // second DataStore on the same file throws "multiple DataStores active".
        val container = (applicationContext as AgoraApplication)
            .awaitContainer()
            ?: return@withContext Result.failure(
                workDataOf("error" to "Database unavailable"),
            )

        // Same process-wide lock as RagManager's in-app runner: never compute alongside it.
        EmbeddingCacheLocks.forModel(modelId).withLock {
            cacheModel(modelId, container.chatDao, container.settingsManager)
        }
    }

    private suspend fun cacheModel(
        modelId: String,
        chatDao: ChatDao,
        settingsManager: SettingsManager,
    ): Result {
        val models = settingsManager.embeddingModels.first()
        val model = models.find { it.id == modelId }
        if (model == null) {
            DebugLog.w(TAG, "Model $modelId not found")
            return Result.failure()
        }

        // Check cancellation
        if (isStopped) return Result.failure()

        val total = chatDao.getIndexableMessageCount()
        if (total == 0) {
            return Result.success(Data.Builder()
                .putInt(KEY_CACHED, 0).putInt(KEY_TOTAL, 0).putInt(KEY_FAILED, 0).build())
        }

        val alreadyDone = chatDao.getEmbeddingCountByModel(modelId).coerceAtMost(total)
        if (alreadyDone >= total) {
            return Result.success(Data.Builder()
                .putInt(KEY_CACHED, total).putInt(KEY_TOTAL, total).putInt(KEY_FAILED, 0).build())
        }

        var succeeded = 0
        var attempted = 0
        val batchSize = model.batchSize.coerceIn(1, 100)
        val remoteConfig = if (model.type == EmbeddingModelType.LOCAL) {
            if (!LlamaEngine.isModelReady(model.localFilePath)) {
                return Result.failure(Data.Builder()
                    .putString("error", "Local model file not found").build())
            }
            null
        } else {
            val apiKey = model.remoteApiKey.ifBlank { resolveApiKey(settingsManager) ?: "" }
            if (apiKey.isBlank()) {
                return Result.failure(Data.Builder()
                    .putString("error", "No API key configured").build())
            }
            apiKey to model.remoteBaseUrl.ifBlank { resolveBaseUrl(settingsManager) }
        }

        try {
            setProgress(workDataOf(KEY_CACHED to alreadyDone, KEY_TOTAL to total))
            var afterMessageId: String? = null
            while (true) {
                if (isStopped) return Result.failure()
                val batch = chatDao.getUnembeddedMessagesPage(
                    modelId = modelId,
                    afterId = afterMessageId,
                    limit = batchSize,
                )
                if (batch.isEmpty()) break
                afterMessageId = batch.last().id

                val texts = batch.map { it.text.take(Constants.MAX_EMBEDDING_TEXT_LENGTH) }
                val embeddings = if (model.type == EmbeddingModelType.LOCAL) {
                    LlamaEngine.computeEmbeddings(texts, model.localFilePath)
                } else {
                    val (apiKey, baseUrl) = requireNotNull(remoteConfig)
                    EmbeddingClient.computeEmbeddings(
                        texts, apiKey, model.remoteModelName, baseUrl
                    )
                }

                attempted += batch.size
                batch.zip(embeddings).forEach { (message, embedding) ->
                    if (embedding != null) {
                        chatDao.upsertEmbedding(EmbeddingEntity(
                            messageId = message.id,
                            modelId = modelId,
                            embedding = EmbeddingIndexer.floatsToBytes(embedding),
                            chunkText = message.text.take(Constants.MAX_CHUNK_TEXT_LENGTH),
                            dimension = embedding.size,
                        ))
                        succeeded++
                    }
                }
                val completed = (alreadyDone + attempted).coerceAtMost(total)
                setProgress(workDataOf(KEY_CACHED to completed, KEY_TOTAL to total))
            }
        } catch (e: Exception) {
            DebugLog.e(TAG, "Cache worker failed", e)
            val displayError = replaceCustomProviderIdsForDisplay(
                e.localizedMessage ?: "Unknown error",
                settingsManager.customProviders.first(),
            )
            return Result.failure(Data.Builder()
                .putString("error", displayError).build())
        }

        val failed = attempted - succeeded
        DebugLog.d(TAG, "Cache complete: $succeeded/$total cached, $failed failed")
        return Result.success(Data.Builder()
            .putInt(KEY_CACHED, (alreadyDone + succeeded).coerceAtMost(total))
            .putInt(KEY_TOTAL, total)
            .putInt(KEY_FAILED, failed)
            .build())
    }

    private suspend fun resolveApiKey(settingsManager: SettingsManager): String? {
        val keys = settingsManager.apiKeys.first()
        for (entry in keys) {
            if (ProviderDefaults.isOpenAiCompatibleEmbedding(entry.provider)) {
                return entry.key
            }
        }
        return keys.firstOrNull()?.key
    }

    private suspend fun resolveBaseUrl(settingsManager: SettingsManager): String {
        return ProviderDefaults.openAiCompatibleBaseUrl(settingsManager.providerBaseUrls.first())
    }
}
