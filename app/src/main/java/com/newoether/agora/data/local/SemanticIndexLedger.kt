package com.newoether.agora.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

@Entity(tableName = "semantic_index_ledger")
data class SemanticIndexLedgerEntity(
    @PrimaryKey val modelId: String,
    val state: String = STATE_NEEDS_RECONCILE,
    val sourceRevision: Long = 0L,
    val completedRevision: Long = 0L,
    val updatedAt: Long,
) {
    init {
        require(modelId.isNotBlank())
        require(state == STATE_NEEDS_RECONCILE || state == STATE_PENDING || state == STATE_CURRENT)
        require(sourceRevision >= 0L)
        require(completedRevision in 0L..sourceRevision)
        require(state != STATE_CURRENT || completedRevision == sourceRevision)
    }

    companion object {
        const val STATE_NEEDS_RECONCILE = "NEEDS_RECONCILE"
        const val STATE_PENDING = "PENDING"
        const val STATE_CURRENT = "CURRENT"
    }
}

@Entity(
    tableName = "semantic_index_work",
    primaryKeys = ["modelId", "messageId"],
    foreignKeys = [
        ForeignKey(
            entity = SemanticIndexLedgerEntity::class,
            parentColumns = ["modelId"],
            childColumns = ["modelId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["modelId", "sourceRevision", "messageId"])],
)
data class SemanticIndexWorkEntity(
    val modelId: String,
    val messageId: String,
    /** Null marks an embedding deletion; non-null fingerprints fence generated content. */
    val sourceFingerprint: String?,
    val sourceRevision: Long,
    val updatedAt: Long,
) {
    init {
        require(modelId.isNotBlank())
        require(messageId.isNotBlank())
        require(sourceFingerprint == null || sourceFingerprint.isNotBlank())
        require(sourceRevision > 0L)
    }
}

private fun requireSemanticModelId(modelId: String) {
    require(modelId.isNotBlank())
}

@Dao
interface SemanticIndexDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLedger(ledger: SemanticIndexLedgerEntity): Long

    @Query("SELECT * FROM semantic_index_ledger WHERE modelId = :modelId")
    suspend fun getLedger(modelId: String): SemanticIndexLedgerEntity?

    @Query(
        """
        UPDATE semantic_index_ledger
        SET state = CASE
                WHEN state = 'NEEDS_RECONCILE' THEN 'NEEDS_RECONCILE'
                ELSE 'PENDING'
            END,
            sourceRevision = sourceRevision + 1,
            updatedAt = :updatedAt
        WHERE modelId = :modelId
        """,
    )
    suspend fun advanceForExactWork(modelId: String, updatedAt: Long): Int

    @Query(
        """
        UPDATE semantic_index_ledger
        SET state = 'NEEDS_RECONCILE',
            sourceRevision = sourceRevision + 1,
            updatedAt = :updatedAt
        WHERE modelId = :modelId
        """,
    )
    suspend fun advanceForReconcile(modelId: String, updatedAt: Long): Int

    @Upsert
    suspend fun upsertWork(work: SemanticIndexWorkEntity)

    @Query("DELETE FROM semantic_index_work WHERE modelId = :modelId")
    suspend fun deleteWorkForModel(modelId: String): Int

    @Query(
        """
        DELETE FROM semantic_index_work
        WHERE modelId = :modelId AND messageId = :messageId
          AND sourceRevision = :sourceRevision
          AND (
              (sourceFingerprint IS NULL AND :sourceFingerprint IS NULL)
              OR sourceFingerprint = :sourceFingerprint
          )
        """,
    )
    suspend fun deleteMatchingWork(
        modelId: String,
        messageId: String,
        sourceFingerprint: String?,
        sourceRevision: Long,
    ): Int

    @Query(
        """
        UPDATE semantic_index_ledger
        SET state = 'CURRENT', completedRevision = :sourceRevision, updatedAt = :updatedAt
        WHERE modelId = :modelId AND state = 'PENDING'
          AND sourceRevision = :sourceRevision
          AND NOT EXISTS (
              SELECT 1 FROM semantic_index_work WHERE modelId = :modelId
          )
        """,
    )
    suspend fun markCurrentAfterExactWork(
        modelId: String,
        sourceRevision: Long,
        updatedAt: Long,
    ): Int

    @Query(
        """
        UPDATE semantic_index_ledger
        SET state = 'CURRENT', completedRevision = :expectedRevision, updatedAt = :updatedAt
        WHERE modelId = :modelId AND state = 'NEEDS_RECONCILE'
          AND sourceRevision = :expectedRevision
          AND NOT EXISTS (
              SELECT 1 FROM semantic_index_work WHERE modelId = :modelId
          )
        """,
    )
    suspend fun markCurrentAfterReconcile(
        modelId: String,
        expectedRevision: Long,
        updatedAt: Long,
    ): Int

    @Query("DELETE FROM semantic_index_ledger WHERE modelId = :modelId")
    suspend fun deleteModel(modelId: String): Int

    @Transaction
    suspend fun admitModel(modelId: String, updatedAt: Long): SemanticIndexLedgerEntity {
        requireSemanticModelId(modelId)
        insertLedger(
            SemanticIndexLedgerEntity(
                modelId = modelId,
                updatedAt = updatedAt,
            ),
        )
        return checkNotNull(getLedger(modelId)) {
            "Semantic index ledger was not readable after admission"
        }
    }

    @Transaction
    suspend fun requestReconcile(modelId: String, updatedAt: Long): SemanticIndexLedgerEntity {
        admitModel(modelId, updatedAt)
        check(advanceForReconcile(modelId, updatedAt) == 1) {
            "Semantic index ledger disappeared during reconcile invalidation"
        }
        deleteWorkForModel(modelId)
        return checkNotNull(getLedger(modelId)) {
            "Semantic index ledger was not readable after reconcile invalidation"
        }
    }

    @Transaction
    suspend fun enqueueExactWork(
        modelId: String,
        messageId: String,
        sourceFingerprint: String?,
        updatedAt: Long,
    ): SemanticIndexLedgerEntity {
        requireSemanticModelId(modelId)
        require(messageId.isNotBlank())
        require(sourceFingerprint == null || sourceFingerprint.isNotBlank())
        admitModel(modelId, updatedAt)
        check(advanceForExactWork(modelId, updatedAt) == 1) {
            "Semantic index ledger disappeared during exact invalidation"
        }
        val ledger = checkNotNull(getLedger(modelId)) {
            "Semantic index ledger was not readable after exact invalidation"
        }
        if (ledger.state != SemanticIndexLedgerEntity.STATE_NEEDS_RECONCILE) {
            upsertWork(
                SemanticIndexWorkEntity(
                    modelId = modelId,
                    messageId = messageId,
                    sourceFingerprint = sourceFingerprint,
                    sourceRevision = ledger.sourceRevision,
                    updatedAt = updatedAt,
                ),
            )
        }
        return ledger
    }

    @Transaction
    suspend fun completeExactWork(work: SemanticIndexWorkEntity, updatedAt: Long): Boolean {
        if (
            deleteMatchingWork(
                modelId = work.modelId,
                messageId = work.messageId,
                sourceFingerprint = work.sourceFingerprint,
                sourceRevision = work.sourceRevision,
            ) != 1
        ) {
            return false
        }
        markCurrentAfterExactWork(
            modelId = work.modelId,
            sourceRevision = work.sourceRevision,
            updatedAt = updatedAt,
        )
        return true
    }

    @Transaction
    suspend fun completeReconcile(
        modelId: String,
        expectedRevision: Long,
        updatedAt: Long,
    ): Boolean {
        requireSemanticModelId(modelId)
        require(expectedRevision >= 0L)
        return markCurrentAfterReconcile(modelId, expectedRevision, updatedAt) == 1
    }
}
