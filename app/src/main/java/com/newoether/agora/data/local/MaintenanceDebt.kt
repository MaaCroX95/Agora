package com.newoether.agora.data.local

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "maintenance_debt",
    primaryKeys = ["kind", "identity"],
    indices = [Index(value = ["state", "updatedAt", "kind", "identity"])],
)
data class MaintenanceDebtEntity(
    val kind: String,
    val identity: String,
    val state: String = STATE_PENDING,
    val revision: Long = 1L,
    val updatedAt: Long,
    val claimId: String? = null,
    val claimedAt: Long? = null,
) {
    init {
        require(kind.isNotBlank())
        require(identity.isNotBlank())
        require(state == STATE_PENDING || state == STATE_CLAIMED)
        require(revision > 0L)
        require((claimId == null) == (claimedAt == null))
        require((state == STATE_CLAIMED) == (claimId != null))
    }

    companion object {
        const val STATE_PENDING = "PENDING"
        const val STATE_CLAIMED = "CLAIMED"
        const val RECONCILE_IDENTITY = "*"

        const val KIND_ATTACHMENT_ORPHANS = "ATTACHMENT_ORPHANS"
        const val KIND_EMBEDDING_ORPHANS = "EMBEDDING_ORPHANS"
        const val KIND_RUN_BRANCHES = "RUN_BRANCHES"

        val RECONCILE_KINDS = listOf(
            KIND_ATTACHMENT_ORPHANS,
            KIND_EMBEDDING_ORPHANS,
            KIND_RUN_BRANCHES,
        )
    }
}
