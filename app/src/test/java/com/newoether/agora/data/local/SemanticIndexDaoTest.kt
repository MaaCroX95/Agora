package com.newoether.agora.data.local

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SemanticIndexDaoTest {
    @Test
    fun admitModelCreatesNeedsReconcileLedgerWithoutScanningContent() = runTest {
        val dao = mockk<SemanticIndexDao>()
        val ledger = needsReconcile(revision = 0, updatedAt = 10)
        coEvery { dao.admitModel(any(), any()) } coAnswers { callOriginal() }
        coEvery { dao.insertLedger(ledger) } returns 1L
        coEvery { dao.getLedger(MODEL_ID) } returns ledger

        assertEquals(ledger, dao.admitModel(MODEL_ID, updatedAt = 10))

        coVerifyOrder {
            dao.insertLedger(ledger)
            dao.getLedger(MODEL_ID)
        }
    }

    @Test
    fun reconcileInvalidationAdvancesRevisionAndClearsExactWork() = runTest {
        val dao = mockk<SemanticIndexDao>()
        val admission = needsReconcile(revision = 0, updatedAt = 20)
        val current = current(revision = 4, updatedAt = 10)
        val invalidated = needsReconcile(revision = 5, completedRevision = 4, updatedAt = 20)
        coEvery { dao.requestReconcile(any(), any()) } coAnswers { callOriginal() }
        coEvery { dao.admitModel(any(), any()) } coAnswers { callOriginal() }
        coEvery { dao.insertLedger(admission) } returns -1L
        coEvery { dao.getLedger(MODEL_ID) } returnsMany listOf(current, invalidated)
        coEvery { dao.advanceForReconcile(MODEL_ID, 20) } returns 1
        coEvery { dao.deleteWorkForModel(MODEL_ID) } returns 3

        assertEquals(invalidated, dao.requestReconcile(MODEL_ID, updatedAt = 20))

        coVerifyOrder {
            dao.insertLedger(admission)
            dao.getLedger(MODEL_ID)
            dao.advanceForReconcile(MODEL_ID, 20)
            dao.deleteWorkForModel(MODEL_ID)
            dao.getLedger(MODEL_ID)
        }
    }

    @Test
    fun exactInvalidationDoesNotMultiplyWorkDuringReconcile() = runTest {
        val dao = mockk<SemanticIndexDao>()
        val admitted = needsReconcile(revision = 2, completedRevision = 1, updatedAt = 10)
        val advanced = needsReconcile(revision = 3, completedRevision = 1, updatedAt = 20)
        coEvery { dao.enqueueExactWork(any(), any(), any(), any()) } coAnswers { callOriginal() }
        coEvery { dao.admitModel(any(), any()) } coAnswers { callOriginal() }
        coEvery {
            dao.insertLedger(needsReconcile(revision = 0, updatedAt = 20))
        } returns -1L
        coEvery { dao.getLedger(MODEL_ID) } returnsMany listOf(admitted, advanced)
        coEvery { dao.advanceForExactWork(MODEL_ID, 20) } returns 1

        assertEquals(
            advanced,
            dao.enqueueExactWork(MODEL_ID, MESSAGE_ID, "fingerprint", updatedAt = 20),
        )

        coVerify(exactly = 0) { dao.upsertWork(any()) }
    }

    @Test
    fun exactInvalidationUpsertsNewestFingerprintAndTombstone() = runTest {
        val dao = mockk<SemanticIndexDao>()
        val current = current(revision = 4, updatedAt = 10)
        val firstPending = pending(revision = 5, completedRevision = 4, updatedAt = 20)
        val secondPending = pending(revision = 6, completedRevision = 4, updatedAt = 21)
        val firstWork = work("fingerprint", revision = 5, updatedAt = 20)
        val tombstone = work(null, revision = 6, updatedAt = 21)
        coEvery { dao.enqueueExactWork(any(), any(), any(), any()) } coAnswers { callOriginal() }
        coEvery { dao.admitModel(any(), any()) } coAnswers { callOriginal() }
        coEvery { dao.insertLedger(any()) } returns -1L
        coEvery { dao.getLedger(MODEL_ID) } returnsMany listOf(
            current,
            firstPending,
            firstPending,
            secondPending,
        )
        coEvery { dao.advanceForExactWork(MODEL_ID, 20) } returns 1
        coEvery { dao.advanceForExactWork(MODEL_ID, 21) } returns 1
        coEvery { dao.upsertWork(firstWork) } returns Unit
        coEvery { dao.upsertWork(tombstone) } returns Unit

        assertEquals(
            firstPending,
            dao.enqueueExactWork(MODEL_ID, MESSAGE_ID, "fingerprint", updatedAt = 20),
        )
        assertEquals(
            secondPending,
            dao.enqueueExactWork(MODEL_ID, MESSAGE_ID, null, updatedAt = 21),
        )

        coVerifyOrder {
            dao.upsertWork(firstWork)
            dao.upsertWork(tombstone)
        }
    }

    @Test
    fun exactCompletionRequiresMatchingRevisionAndFingerprint() = runTest {
        val dao = mockk<SemanticIndexDao>()
        val stale = work("old", revision = 4, updatedAt = 10)
        val current = work("new", revision = 5, updatedAt = 20)
        coEvery { dao.completeExactWork(any(), any()) } coAnswers { callOriginal() }
        coEvery {
            dao.deleteMatchingWork(MODEL_ID, MESSAGE_ID, "old", 4)
        } returns 0
        coEvery {
            dao.deleteMatchingWork(MODEL_ID, MESSAGE_ID, "new", 5)
        } returns 1
        coEvery { dao.markCurrentAfterExactWork(MODEL_ID, 5, 30) } returns 1

        assertFalse(dao.completeExactWork(stale, updatedAt = 29))
        assertTrue(dao.completeExactWork(current, updatedAt = 30))

        coVerify(exactly = 0) { dao.markCurrentAfterExactWork(MODEL_ID, 4, any()) }
        coVerify(exactly = 1) { dao.markCurrentAfterExactWork(MODEL_ID, 5, 30) }
    }

    @Test
    fun reconcileCompletionUsesExpectedRevisionCas() = runTest {
        val dao = mockk<SemanticIndexDao>()
        coEvery { dao.completeReconcile(any(), any(), any()) } coAnswers { callOriginal() }
        coEvery { dao.markCurrentAfterReconcile(MODEL_ID, 7, 30) } returns 1
        coEvery { dao.markCurrentAfterReconcile(MODEL_ID, 6, 31) } returns 0

        assertTrue(dao.completeReconcile(MODEL_ID, expectedRevision = 7, updatedAt = 30))
        assertFalse(dao.completeReconcile(MODEL_ID, expectedRevision = 6, updatedAt = 31))
    }

    @Test
    fun invalidArgumentsFailBeforeDatabaseAccess() = runTest {
        val dao = mockk<SemanticIndexDao>()
        coEvery { dao.admitModel(any(), any()) } coAnswers { callOriginal() }
        coEvery { dao.enqueueExactWork(any(), any(), any(), any()) } coAnswers { callOriginal() }
        coEvery { dao.completeReconcile(any(), any(), any()) } coAnswers { callOriginal() }

        assertIllegalArgument { dao.admitModel("", updatedAt = 1) }
        assertIllegalArgument { dao.enqueueExactWork(MODEL_ID, "", "fingerprint", 1) }
        assertIllegalArgument { dao.enqueueExactWork(MODEL_ID, MESSAGE_ID, "", 1) }
        assertIllegalArgument { dao.completeReconcile("", expectedRevision = 0, updatedAt = 1) }
        assertIllegalArgument { dao.completeReconcile(MODEL_ID, expectedRevision = -1, updatedAt = 1) }

        coVerify(exactly = 0) { dao.insertLedger(any()) }
        coVerify(exactly = 0) { dao.advanceForExactWork(any(), any()) }
        coVerify(exactly = 0) { dao.markCurrentAfterReconcile(any(), any(), any()) }
    }

    @Test
    fun roomQueriesCarryQueueAndCompletionFences() {
        val source = sourceFile(
            "app/src/main/java/com/newoether/agora/data/local/SemanticIndexLedger.kt",
        ).replace("\r\n", "\n")
        val delete = source.substringBefore("suspend fun deleteMatchingWork")
            .substringAfterLast("@Query(")
        val exactCurrent = source.substringBefore("suspend fun markCurrentAfterExactWork")
            .substringAfterLast("@Query(")
        val reconcileCurrent = source.substringBefore("suspend fun markCurrentAfterReconcile")
            .substringAfterLast("@Query(")

        assertTrue(delete.contains("sourceRevision = :sourceRevision"))
        assertTrue(delete.contains("sourceFingerprint IS NULL AND :sourceFingerprint IS NULL"))
        assertTrue(exactCurrent.contains("state = 'PENDING'"))
        assertTrue(exactCurrent.contains("sourceRevision = :sourceRevision"))
        assertTrue(exactCurrent.contains("NOT EXISTS"))
        assertTrue(reconcileCurrent.contains("state = 'NEEDS_RECONCILE'"))
        assertTrue(reconcileCurrent.contains("sourceRevision = :expectedRevision"))
        assertTrue(reconcileCurrent.contains("NOT EXISTS"))
    }

    private fun needsReconcile(
        revision: Long,
        completedRevision: Long = 0,
        updatedAt: Long,
    ) = SemanticIndexLedgerEntity(
        modelId = MODEL_ID,
        sourceRevision = revision,
        completedRevision = completedRevision,
        updatedAt = updatedAt,
    )

    private fun pending(
        revision: Long,
        completedRevision: Long,
        updatedAt: Long,
    ) = SemanticIndexLedgerEntity(
        modelId = MODEL_ID,
        state = SemanticIndexLedgerEntity.STATE_PENDING,
        sourceRevision = revision,
        completedRevision = completedRevision,
        updatedAt = updatedAt,
    )

    private fun current(revision: Long, updatedAt: Long) = SemanticIndexLedgerEntity(
        modelId = MODEL_ID,
        state = SemanticIndexLedgerEntity.STATE_CURRENT,
        sourceRevision = revision,
        completedRevision = revision,
        updatedAt = updatedAt,
    )

    private fun work(fingerprint: String?, revision: Long, updatedAt: Long) =
        SemanticIndexWorkEntity(
            modelId = MODEL_ID,
            messageId = MESSAGE_ID,
            sourceFingerprint = fingerprint,
            sourceRevision = revision,
            updatedAt = updatedAt,
        )

    private suspend fun assertIllegalArgument(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    private fun sourceFile(relativePath: String): String {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            File(directory, relativePath).takeIf(File::isFile)?.let { return it.readText() }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate $relativePath")
    }

    companion object {
        private const val MODEL_ID = "model"
        private const val MESSAGE_ID = "message"
    }
}
