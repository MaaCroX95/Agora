package com.newoether.agora.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
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
import com.newoether.agora.data.local.ReconcileIndexableMessage
import com.newoether.agora.data.local.SemanticIndexLedgerEntity
import com.newoether.agora.data.local.markSemanticEmbeddingReused
import com.newoether.agora.data.local.commitSemanticEmbedding
import com.newoether.agora.data.local.semanticSourceFingerprint
import com.newoether.agora.data.replaceCustomProviderIdsForDisplay
import com.newoether.agora.util.Constants
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.math.max

internal enum class EmbeddingCacheScheduleDecision {
    KEEP_NEW,
    APPEND_FOLLOWER,
    REPLACE_LEGACY_CHAIN,
    NO_OP,
}

internal fun embeddingCacheScheduleDecision(
    states: List<WorkInfo.State>,
): EmbeddingCacheScheduleDecision {
    val unfinished = states.filterNot(WorkInfo.State::isFinished)
    val runningCount = unfinished.count { it == WorkInfo.State.RUNNING }
    return when {
        unfinished.size > 2 || runningCount > 1 ->
            EmbeddingCacheScheduleDecision.REPLACE_LEGACY_CHAIN
        unfinished.any { it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.BLOCKED } ->
            EmbeddingCacheScheduleDecision.NO_OP
        runningCount == 1 -> EmbeddingCacheScheduleDecision.APPEND_FOLLOWER
        else -> EmbeddingCacheScheduleDecision.KEEP_NEW
    }
}

private data class EmbeddingCandidate(
    val workRevision: Long?,
    val fingerprint: String,
    val message: IndexableMessage,
    val existingEmbeddingId: Long? = null,
)

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

    private var lastProgressPermille = 0
    private var lastProgressCached: Int? = null
    private var lastProgressTotal: Int? = null
    private var progressPublishCount = 0

    internal suspend fun cacheModel(
        modelId: String,
        database: ChatDatabase,
        settingsManager: SettingsManager,
    ): Result {
        val model = settingsManager.embeddingModels.first().find { it.id == modelId }
            ?: return Result.success()
        val semanticDao = database.semanticIndexDao()

        return try {
            while (true) {
                val ledger = semanticDao.getLedger(modelId) ?: return Result.success()
                if (ledger.state == SemanticIndexLedgerEntity.STATE_CURRENT) {
                    publishProgress(
                        database = database,
                        modelId = modelId,
                        processed = 1,
                        workTotal = 1,
                        terminal = true,
                    )
                    return Result.success(workDataOf(KEY_FAILED to 0))
                }

                val completed = when (ledger.state) {
                    SemanticIndexLedgerEntity.STATE_PENDING ->
                        consumeExactWork(model, settingsManager, database)

                    SemanticIndexLedgerEntity.STATE_NEEDS_RECONCILE ->
                        reconcileModel(
                            model = model,
                            settingsManager = settingsManager,
                            expectedReconcileRevision = ledger.reconcileRevision,
                            database = database,
                        )

                    else -> true
                }
                if (!completed) {
                    val latest = semanticDao.getLedger(modelId)
                    val reconcileWasSuperseded =
                        ledger.state == SemanticIndexLedgerEntity.STATE_NEEDS_RECONCILE &&
                            latest?.reconcileRevision != ledger.reconcileRevision
                    if (!reconcileWasSuperseded) return Result.retry()
                }
                // Reconcile can hand off directly to exact work that arrived during the scan.
                // Stay in this worker so frequent messages do not create or wait behind a chain.
                yield()
            }
            @Suppress("UNREACHABLE_CODE")
            Result.retry()
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
        val workTotal = semanticDao.getWorkCount(model.id).coerceAtLeast(1)
        var processed = 0
        var afterRevision = 0L
        var afterMessageId = ""
        var embeddingConfigResolved = false
        var remoteConfig: Pair<String, String>? = null
        var complete = true
        publishProgress(database, model.id, processed, workTotal)
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
            val candidates = mutableListOf<EmbeddingCandidate>()
            page.forEach { work ->
                if (work.sourceFingerprint == null) {
                    semanticDao.completeExactWork(work, System.currentTimeMillis())
                } else {
                    val text = semanticDao.getSearchableMessageText(work.messageId)
                    if (text != null && semanticSourceFingerprint(text) == work.sourceFingerprint) {
                        candidates += EmbeddingCandidate(
                            workRevision = work.sourceRevision,
                            fingerprint = work.sourceFingerprint,
                            message = IndexableMessage(work.messageId, text),
                        )
                    } else {
                        // A newer mutation either replaced this work row or made the source
                        // ineligible. Complete only the exact row we actually observed.
                        semanticDao.completeExactWork(work, System.currentTimeMillis())
                    }
                }
            }
            if (candidates.isNotEmpty() && !embeddingConfigResolved) {
                remoteConfig = resolveEmbeddingConfig(model, settingsManager)
                embeddingConfigResolved = true
            }
            if (!embedCandidates(model, remoteConfig, candidates, database)) complete = false
            processed += page.size
            publishProgress(database, model.id, processed, max(workTotal, processed))
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
        expectedReconcileRevision: Long,
        database: ChatDatabase,
    ): Boolean {
        val chatDao = database.chatDao()
        val semanticDao = database.semanticIndexDao()
        val workTotal = chatDao.getIndexableMessageCount().coerceAtLeast(1)
        var processed = 0
        var afterMessageId: String? = null
        var embeddingConfigResolved = false
        var remoteConfig: Pair<String, String>? = null
        var complete = true
        publishProgress(database, model.id, processed, workTotal)
        while (true) {
            val page = semanticDao.getReconcileMessagesPage(
                modelId = model.id,
                afterId = afterMessageId,
                limit = model.batchSize.coerceIn(1, MAX_BATCH_SIZE),
            )
            if (page.isEmpty()) break
            afterMessageId = page.last().id
            val candidates = mutableListOf<EmbeddingCandidate>()
            page.forEach { row ->
                val fingerprint = semanticSourceFingerprint(row.text)
                val validShape = row.dimension != null && row.dimension > 0 &&
                    row.embeddingBytes == row.dimension * Float.SIZE_BYTES
                val fingerprintMatches =
                    row.embeddingId != null && validShape &&
                        row.embeddingFingerprint == fingerprint
                val safeLegacyReuse =
                    row.embeddingId != null && validShape &&
                        row.embeddingFingerprint == null &&
                        row.text.length <= Constants.MAX_CHUNK_TEXT_LENGTH &&
                        row.chunkText == row.text
                when {
                    fingerprintMatches -> Unit
                    safeLegacyReuse -> {
                        val retained = database.markSemanticEmbeddingReused(
                            embeddingId = checkNotNull(row.embeddingId),
                            modelId = model.id,
                            messageId = row.id,
                            expectedFingerprint = fingerprint,
                            expectedReconcileRevision = expectedReconcileRevision,
                        )
                        if (!retained) {
                            candidates += row.toCandidate(fingerprint)
                        }
                    }
                    else -> candidates += row.toCandidate(fingerprint)
                }
            }
            if (candidates.isNotEmpty() && !embeddingConfigResolved) {
                remoteConfig = resolveEmbeddingConfig(model, settingsManager)
                embeddingConfigResolved = true
            }
            if (
                !embedCandidates(
                    model = model,
                    remoteConfig = remoteConfig,
                    candidates = candidates,
                    database = database,
                    expectedReconcileRevision = expectedReconcileRevision,
                )
            ) {
                complete = false
            }
            processed += page.size
            publishProgress(database, model.id, processed, max(workTotal, processed))
            yield()
        }
        if (!complete) return false
        return semanticDao.completeReconcile(
            modelId = model.id,
            expectedReconcileRevision = expectedReconcileRevision,
            updatedAt = System.currentTimeMillis(),
        )
    }

    private suspend fun embedCandidates(
        model: EmbeddingModelConfig,
        remoteConfig: Pair<String, String>?,
        candidates: List<EmbeddingCandidate>,
        database: ChatDatabase,
        expectedReconcileRevision: Long? = null,
    ): Boolean {
        if (candidates.isEmpty()) return true
        val texts = candidates.map { candidate ->
            candidate.message.text.take(Constants.MAX_EMBEDDING_TEXT_LENGTH)
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
        if (embeddings.size != candidates.size) return false
        candidates.forEachIndexed { index, candidate ->
            val embedding = embeddings[index] ?: return false
            database.commitSemanticEmbedding(
                embedding = EmbeddingEntity(
                    id = candidate.existingEmbeddingId ?: 0L,
                    messageId = candidate.message.id,
                    modelId = model.id,
                    embedding = EmbeddingIndexer.floatsToBytes(embedding),
                    chunkText = candidate.message.text.take(Constants.MAX_CHUNK_TEXT_LENGTH),
                    dimension = embedding.size,
                    sourceFingerprint = candidate.fingerprint,
                ),
                expectedFingerprint = candidate.fingerprint,
                expectedWorkRevision = candidate.workRevision,
                expectedReconcileRevision = expectedReconcileRevision,
                completePendingWork = candidate.workRevision != null,
                updatedAt = System.currentTimeMillis(),
            )
            // A false commit means the source/work/reconcile generation was superseded. It is not
            // an embedding failure; the ledger already owns the newer work and this worker loops.
        }
        return true
    }

    private fun ReconcileIndexableMessage.toCandidate(
        fingerprint: String,
    ): EmbeddingCandidate = EmbeddingCandidate(
        workRevision = null,
        fingerprint = fingerprint,
        message = IndexableMessage(id, text),
        existingEmbeddingId = embeddingId,
    )

    private suspend fun publishProgress(
        database: ChatDatabase,
        modelId: String,
        processed: Int,
        workTotal: Int,
        terminal: Boolean = false,
    ) {
        progressPublishCount += 1
        val refreshCounts =
            terminal ||
                lastProgressCached == null ||
                lastProgressTotal == null ||
                progressPublishCount % PROGRESS_COUNT_REFRESH_INTERVAL == 0
        if (refreshCounts) {
            val chatDao = database.chatDao()
            val total = chatDao.getIndexableMessageCount()
            lastProgressTotal = total
            lastProgressCached = chatDao.getEmbeddingCountByModel(modelId).coerceAtMost(total)
        }
        val total = checkNotNull(lastProgressTotal)
        val cached = checkNotNull(lastProgressCached)
        val rawPermille = if (terminal) {
            1000
        } else {
            ((processed.toLong() * 990L) / workTotal.coerceAtLeast(1)).toInt()
                .coerceIn(0, 990)
        }
        lastProgressPermille = max(lastProgressPermille, rawPermille)
        setProgress(
            workDataOf(
                KEY_PROCESSED to processed,
                KEY_WORK_TOTAL to workTotal,
                KEY_CACHED to cached,
                KEY_TOTAL to total,
                KEY_PROGRESS_PERMILLE to lastProgressPermille,
            ),
        )
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
        const val KEY_PROCESSED = "processed"
        const val KEY_WORK_TOTAL = "work_total"
        const val KEY_CACHED = "cached"
        const val KEY_TOTAL = "total"
        const val KEY_PROGRESS_PERMILLE = "progress_permille"
        const val KEY_FAILED = "failed"
        const val KEY_ERROR = "error"
        const val TAG = "EmbeddingCache"
        private const val MAX_BATCH_SIZE = 32
        private const val PROGRESS_COUNT_REFRESH_INTERVAL = 16
        private val schedulingLock = Mutex()

        fun workNameFor(modelId: String) = "embedding_cache_$modelId"

        suspend fun schedule(
            modelId: String,
            workManager: WorkManager,
        ) {
            require(modelId.isNotBlank())
            schedulingLock.withLock {
                val infos = workManager.getWorkInfosForUniqueWorkFlow(workNameFor(modelId)).first()
                enqueueForDecision(
                    modelId = modelId,
                    workManager = workManager,
                    decision = embeddingCacheScheduleDecision(infos.map { it.state }),
                )
            }
        }

        /** Collapses pre-v30 APPEND chains without creating work for an otherwise idle model. */
        suspend fun repairLegacyChain(
            modelId: String,
            workManager: WorkManager,
        ) {
            require(modelId.isNotBlank())
            schedulingLock.withLock {
                val infos = workManager.getWorkInfosForUniqueWorkFlow(workNameFor(modelId)).first()
                if (
                    embeddingCacheScheduleDecision(infos.map { it.state }) ==
                    EmbeddingCacheScheduleDecision.REPLACE_LEGACY_CHAIN
                ) {
                    enqueueForDecision(
                        modelId = modelId,
                        workManager = workManager,
                        decision = EmbeddingCacheScheduleDecision.REPLACE_LEGACY_CHAIN,
                    )
                }
            }
        }

        private suspend fun enqueueForDecision(
            modelId: String,
            workManager: WorkManager,
            decision: EmbeddingCacheScheduleDecision,
        ) {
            if (decision == EmbeddingCacheScheduleDecision.NO_OP) return
            val request = OneTimeWorkRequestBuilder<EmbeddingCacheWorker>()
                .setInputData(workDataOf(KEY_MODEL_ID to modelId))
                .addTag(TAG)
                .build()
            val policy = when (decision) {
                EmbeddingCacheScheduleDecision.KEEP_NEW -> ExistingWorkPolicy.KEEP
                EmbeddingCacheScheduleDecision.APPEND_FOLLOWER ->
                    ExistingWorkPolicy.APPEND_OR_REPLACE
                EmbeddingCacheScheduleDecision.REPLACE_LEGACY_CHAIN ->
                    ExistingWorkPolicy.REPLACE
                EmbeddingCacheScheduleDecision.NO_OP -> return
            }
            workManager.enqueueUniqueWork(workNameFor(modelId), policy, request).await()
        }
    }
}
