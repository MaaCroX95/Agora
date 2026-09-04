package com.newoether.agora.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class EmbeddingCacheActionStateTest {
    @Test
    fun initialCountLoadUsesLoadingOnlyUntilSnapshotOrFailure() {
        assertEquals(
            EmbeddingCacheActionState.LOADING,
            embeddingCacheActionState(
                hasSnapshot = false,
                countLoading = true,
                countFailed = false,
                workerActive = false,
                ledgerCurrent = false,
            ),
        )
        assertEquals(
            EmbeddingCacheActionState.RETRY,
            embeddingCacheActionState(
                hasSnapshot = false,
                countLoading = false,
                countFailed = true,
                workerActive = false,
                ledgerCurrent = false,
            ),
        )
    }

    @Test
    fun retainedSnapshotAlwaysOffersAStableActionWhenIdle() {
        assertEquals(
            EmbeddingCacheActionState.CACHE,
            embeddingCacheActionState(
                hasSnapshot = true,
                countLoading = false,
                countFailed = true,
                workerActive = false,
                ledgerCurrent = false,
            ),
        )
        assertEquals(
            EmbeddingCacheActionState.RECACHE,
            embeddingCacheActionState(
                hasSnapshot = true,
                countLoading = false,
                countFailed = false,
                workerActive = false,
                ledgerCurrent = true,
            ),
        )
    }

    @Test
    fun activeWorkerOwnsTheActionEvenWhileCountsRefresh() {
        assertEquals(
            EmbeddingCacheActionState.CACHING,
            embeddingCacheActionState(
                hasSnapshot = false,
                countLoading = true,
                countFailed = false,
                workerActive = true,
                ledgerCurrent = false,
            ),
        )
    }
}
