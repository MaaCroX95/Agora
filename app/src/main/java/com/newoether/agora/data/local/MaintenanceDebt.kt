package com.newoether.agora.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

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

private fun requireClaim(claim: MaintenanceDebtEntity) {
    require(claim.state == MaintenanceDebtEntity.STATE_CLAIMED)
    requireNotNull(claim.claimId)
    requireNotNull(claim.claimedAt)
}

@Dao
interface MaintenanceDebtDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDebt(debt: MaintenanceDebtEntity): Long

    @Query(
        """
        UPDATE maintenance_debt
        SET state = 'PENDING', revision = revision + 1, updatedAt = :updatedAt,
            claimId = NULL, claimedAt = NULL
        WHERE kind = :kind AND identity = :identity
        """,
    )
    suspend fun renewDebt(kind: String, identity: String, updatedAt: Long): Int

    @Query(
        """
        SELECT * FROM maintenance_debt
        WHERE state = 'PENDING'
           OR (state = 'CLAIMED' AND claimedAt <= :staleBefore)
        ORDER BY updatedAt, kind, identity
        LIMIT :limit
        """,
    )
    suspend fun getClaimCandidates(staleBefore: Long, limit: Int): List<MaintenanceDebtEntity>

    @Query(
        """
        UPDATE maintenance_debt
        SET state = 'CLAIMED', claimId = :claimId, claimedAt = :claimedAt
        WHERE kind = :kind AND identity = :identity AND revision = :revision
          AND (
              state = 'PENDING'
              OR (state = 'CLAIMED' AND claimedAt <= :staleBefore)
          )
        """,
    )
    suspend fun claimCandidate(
        kind: String,
        identity: String,
        revision: Long,
        claimId: String,
        claimedAt: Long,
        staleBefore: Long,
    ): Int

    @Query("SELECT * FROM maintenance_debt WHERE kind = :kind AND identity = :identity")
    suspend fun getDebt(kind: String, identity: String): MaintenanceDebtEntity?

    @Query(
        """
        DELETE FROM maintenance_debt
        WHERE kind = :kind AND identity = :identity
          AND state = 'CLAIMED' AND revision = :revision AND claimId = :claimId
        """,
    )
    suspend fun completeClaimed(
        kind: String,
        identity: String,
        revision: Long,
        claimId: String,
    ): Int

    @Query(
        """
        UPDATE maintenance_debt
        SET state = 'PENDING', updatedAt = :updatedAt, claimId = NULL, claimedAt = NULL
        WHERE kind = :kind AND identity = :identity
          AND state = 'CLAIMED' AND revision = :revision AND claimId = :claimId
        """,
    )
    suspend fun releaseClaimed(
        kind: String,
        identity: String,
        revision: Long,
        claimId: String,
        updatedAt: Long,
    ): Int

    @Transaction
    suspend fun enqueue(kind: String, identity: String, updatedAt: Long): MaintenanceDebtEntity {
        require(kind.isNotBlank())
        require(identity.isNotBlank())
        val inserted = insertDebt(
            MaintenanceDebtEntity(
                kind = kind,
                identity = identity,
                updatedAt = updatedAt,
            ),
        )
        if (inserted == INSERT_IGNORED) {
            check(renewDebt(kind, identity, updatedAt) == 1) {
                "Maintenance debt disappeared during enqueue"
            }
        }
        return checkNotNull(getDebt(kind, identity)) {
            "Maintenance debt was not readable after enqueue"
        }
    }

    @Transaction
    suspend fun claim(
        claimId: String,
        claimedAt: Long,
        staleBefore: Long,
        limit: Int,
    ): List<MaintenanceDebtEntity> {
        require(claimId.isNotBlank())
        require(claimedAt >= staleBefore)
        require(limit in 1..MAX_CLAIM_BATCH)
        val claimed = ArrayList<MaintenanceDebtEntity>(limit)
        for (candidate in getClaimCandidates(staleBefore, limit)) {
            if (
                claimCandidate(
                    kind = candidate.kind,
                    identity = candidate.identity,
                    revision = candidate.revision,
                    claimId = claimId,
                    claimedAt = claimedAt,
                    staleBefore = staleBefore,
                ) == 1
            ) {
                val debt = checkNotNull(getDebt(candidate.kind, candidate.identity)) {
                    "Claimed maintenance debt disappeared"
                }
                check(
                    debt.revision == candidate.revision &&
                        debt.claimId == claimId &&
                        debt.claimedAt == claimedAt
                ) { "Maintenance debt claim changed inside its transaction" }
                claimed += debt
            }
        }
        return claimed
    }

    @Transaction
    suspend fun complete(claim: MaintenanceDebtEntity): Boolean {
        requireClaim(claim)
        return completeClaimed(
            kind = claim.kind,
            identity = claim.identity,
            revision = claim.revision,
            claimId = requireNotNull(claim.claimId),
        ) == 1
    }

    @Transaction
    suspend fun release(claim: MaintenanceDebtEntity, updatedAt: Long): Boolean {
        requireClaim(claim)
        return releaseClaimed(
            kind = claim.kind,
            identity = claim.identity,
            revision = claim.revision,
            claimId = requireNotNull(claim.claimId),
            updatedAt = updatedAt,
        ) == 1
    }

    companion object {
        const val MAX_CLAIM_BATCH = 100
        private const val INSERT_IGNORED = -1L
    }
}
