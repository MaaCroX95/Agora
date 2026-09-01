package com.newoether.agora.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(tableName = "semantic_index_ledger")
data class SemanticIndexLedgerEntity(
    @androidx.room.PrimaryKey val modelId: String,
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
