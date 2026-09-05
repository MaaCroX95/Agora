package com.newoether.agora.viewmodel

internal enum class EmbeddingCacheRowPhase {
    LOADING, QUEUED, CACHING, FINALIZING, FAILED, CACHE, RECACHE,
}
internal enum class EmbeddingCacheFailureKind { REFRESH, WORK }
internal data class EmbeddingCacheWorkSnapshot(
    val generationRevision: Long,
    val kind: String,
    val processed: Int,
    val total: Int,
    val remaining: Int,
    val progressPermille: Int,
) {
    init {
        require(
            generationRevision >= 0L && kind.isNotBlank() && total > 0 &&
                processed in 0..total && remaining == total - processed &&
                progressPermille == ((processed.toLong() * 1000L) / total).toInt(),
        )
    }

    val fraction: Float get() = progressPermille / 1000f
}
internal fun embeddingCacheWorkSnapshotOrNull(
    generationRevision: Long,
    kind: String?,
    processed: Int,
    total: Int,
    remaining: Int,
    progressPermille: Int,
): EmbeddingCacheWorkSnapshot? = runCatching {
    EmbeddingCacheWorkSnapshot(
        generationRevision, kind.orEmpty(), processed, total, remaining, progressPermille,
    )
}.getOrNull()
internal data class EmbeddingCacheRowSnapshot(
    val phase: EmbeddingCacheRowPhase,
    val progress: EmbeddingCacheWorkSnapshot? = null,
    val cached: Int? = null,
    val indexableTotal: Int? = null,
    val failure: EmbeddingCacheFailureKind? = null,
) {
    val workActive: Boolean get() =
        phase == EmbeddingCacheRowPhase.QUEUED || phase == EmbeddingCacheRowPhase.CACHING
    val visualPhase: EmbeddingCacheRowPhase
        get() = when (phase) {
            EmbeddingCacheRowPhase.QUEUED -> EmbeddingCacheRowPhase.LOADING
            EmbeddingCacheRowPhase.FINALIZING -> EmbeddingCacheRowPhase.CACHING
            else -> phase
        }

    companion object { val Loading = EmbeddingCacheRowSnapshot(EmbeddingCacheRowPhase.LOADING) }
}
internal object EmbeddingCacheRowReducer {
    fun refreshRequested(previous: EmbeddingCacheRowSnapshot?): EmbeddingCacheRowSnapshot =
        when {
            previous == null -> EmbeddingCacheRowSnapshot.Loading
            previous.phase == EmbeddingCacheRowPhase.FAILED &&
                previous.failure == EmbeddingCacheFailureKind.REFRESH ->
                previous.copy(phase = EmbeddingCacheRowPhase.LOADING, failure = null)
            else -> previous
        }

    fun workActive(
        previous: EmbeddingCacheRowSnapshot?,
        progress: EmbeddingCacheWorkSnapshot?,
    ): EmbeddingCacheRowSnapshot = (previous ?: EmbeddingCacheRowSnapshot.Loading).copy(
        phase = if (progress == null) EmbeddingCacheRowPhase.QUEUED
            else EmbeddingCacheRowPhase.CACHING,
        progress = progress,
        failure = null,
    )

    fun finalizing(previous: EmbeddingCacheRowSnapshot?) =
        (previous ?: EmbeddingCacheRowSnapshot.Loading).copy(
            phase = EmbeddingCacheRowPhase.FINALIZING,
            failure = null,
        )

    fun failed(previous: EmbeddingCacheRowSnapshot?, kind: EmbeddingCacheFailureKind) =
        (previous ?: EmbeddingCacheRowSnapshot.Loading).copy(
            phase = EmbeddingCacheRowPhase.FAILED,
            failure = kind,
        )

    fun refreshed(
        previous: EmbeddingCacheRowSnapshot?, cached: Int, total: Int, ledgerCurrent: Boolean,
    ): EmbeddingCacheRowSnapshot {
        val safeTotal = total.coerceAtLeast(0)
        val safeCached = cached.coerceIn(0, safeTotal)
        val retained = previous ?: EmbeddingCacheRowSnapshot.Loading
        if (retained.workActive || retained.phase == EmbeddingCacheRowPhase.FINALIZING && !ledgerCurrent) {
            return retained.copy(cached = safeCached, indexableTotal = safeTotal)
        }
        if (retained.phase == EmbeddingCacheRowPhase.FAILED &&
            retained.failure == EmbeddingCacheFailureKind.WORK) {
            return retained.copy(cached = safeCached, indexableTotal = safeTotal)
        }
        return EmbeddingCacheRowSnapshot(
            phase = if (ledgerCurrent) EmbeddingCacheRowPhase.RECACHE
                else EmbeddingCacheRowPhase.CACHE,
            cached = safeCached,
            indexableTotal = safeTotal,
        )
    }

    fun refreshFailed(previous: EmbeddingCacheRowSnapshot?): EmbeddingCacheRowSnapshot {
        val retained = previous ?: return failed(null, EmbeddingCacheFailureKind.REFRESH)
        if (retained.workActive || retained.phase == EmbeddingCacheRowPhase.FAILED) return retained
        if (retained.phase != EmbeddingCacheRowPhase.FINALIZING &&
            retained.cached != null && retained.indexableTotal != null) {
            return retained
        }
        return failed(retained, EmbeddingCacheFailureKind.REFRESH)
    }
}
