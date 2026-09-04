package com.newoether.agora.ui.settings

import com.newoether.agora.viewmodel.EmbeddingCacheFailureKind
import com.newoether.agora.viewmodel.EmbeddingCacheRowPhase
import com.newoether.agora.viewmodel.EmbeddingCacheRowReducer
import com.newoether.agora.viewmodel.EmbeddingCacheRowSnapshot
import com.newoether.agora.viewmodel.EmbeddingCacheWorkSnapshot
import com.newoether.agora.viewmodel.embeddingCacheWorkSnapshotOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingCacheActionStateTest {
    @Test
    fun successTraceNeverEmitsIdleBeforeRecache() {
        var row = EmbeddingCacheRowReducer.refreshRequested(null)
        val trace = mutableListOf(row.phase)
        row = EmbeddingCacheRowReducer.workActive(row, null); trace += row.phase
        row = EmbeddingCacheRowReducer.workActive(row, progress(0, 40)); trace += row.phase
        row = EmbeddingCacheRowReducer.workActive(row, progress(10, 40)); trace += row.phase
        row = EmbeddingCacheRowReducer.finalizing(row); trace += row.phase
        assertEquals(EmbeddingCacheRowPhase.CACHING, row.visualPhase)
        row = EmbeddingCacheRowReducer.refreshed(row, 40, 40, true); trace += row.phase
        assertEquals(
            listOf(
                EmbeddingCacheRowPhase.LOADING, EmbeddingCacheRowPhase.QUEUED,
                EmbeddingCacheRowPhase.CACHING, EmbeddingCacheRowPhase.CACHING,
                EmbeddingCacheRowPhase.FINALIZING, EmbeddingCacheRowPhase.RECACHE,
            ),
            trace,
        )
    }

    @Test
    fun followerLoadsAndWorkerFailureRetainsReliableProgress() {
        val active = EmbeddingCacheRowReducer.workActive(null, progress(10, 40))
        val follower = EmbeddingCacheRowReducer.workActive(active, null)
        assertEquals(EmbeddingCacheRowPhase.QUEUED, follower.phase)
        assertEquals(EmbeddingCacheRowPhase.LOADING, follower.visualPhase)
        assertNull(follower.progress)

        val failed = EmbeddingCacheRowReducer.failed(active, EmbeddingCacheFailureKind.WORK)
        val refreshed = EmbeddingCacheRowReducer.refreshed(failed, 30, 40, false)
        assertEquals(EmbeddingCacheRowPhase.FAILED, refreshed.phase)
        assertEquals(30, refreshed.progress?.remaining)
        assertEquals(EmbeddingCacheFailureKind.WORK, refreshed.failure)
    }

    @Test
    fun refreshFailureIsRetryableWithoutDiscardingStableState() {
        val initialFailure = EmbeddingCacheRowReducer.refreshFailed(null)
        assertEquals(EmbeddingCacheRowPhase.FAILED, initialFailure.phase)
        assertEquals(EmbeddingCacheRowPhase.LOADING,
            EmbeddingCacheRowReducer.refreshRequested(initialFailure).phase)
        val stable = EmbeddingCacheRowSnapshot(
            EmbeddingCacheRowPhase.CACHE, cached = 4, indexableTotal = 10,
        )
        assertSame(stable, EmbeddingCacheRowReducer.refreshFailed(stable))
        val finalizing = EmbeddingCacheRowReducer.finalizing(
            EmbeddingCacheRowReducer.workActive(stable, progress(10, 40)),
        )
        val failedFinalRefresh = EmbeddingCacheRowReducer.refreshFailed(finalizing)
        assertEquals(EmbeddingCacheRowPhase.FAILED, failedFinalRefresh.phase)
        assertEquals(EmbeddingCacheFailureKind.REFRESH, failedFinalRefresh.failure)
    }

    @Test
    fun staleRefreshAndIncoherentWorkerPayloadCannotCorruptActiveState() {
        val progress = progress(7, 20)
        val active = EmbeddingCacheRowReducer.workActive(null, progress)
        val refreshed = EmbeddingCacheRowReducer.refreshed(active, 20, 20, true)
        val finalizing = EmbeddingCacheRowReducer.finalizing(active)
        assertEquals(EmbeddingCacheRowPhase.FINALIZING, EmbeddingCacheRowReducer.refreshed(finalizing, 20, 20, false).phase)
        assertEquals(EmbeddingCacheRowPhase.CACHING, refreshed.phase)
        assertTrue(refreshed.progress == progress && refreshed.workActive)
        assertNull(workSnapshot(remaining = 5, permille = 400))
        assertNull(workSnapshot(remaining = 6, permille = 989))
    }

    private fun workSnapshot(remaining: Int, permille: Int) =
        embeddingCacheWorkSnapshotOrNull(3, "EXACT", 4, 10, remaining, permille)

    private fun progress(processed: Int, total: Int) = EmbeddingCacheWorkSnapshot(
        7, "RECONCILE", processed, total, total - processed, processed * 1000 / total,
    )
}
