package com.swipedelete.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.swipedelete.core.database.entity.JournalEntryEntity
import com.swipedelete.core.model.JournalState

/**
 * Write-ahead journal access (AC-1). Deliberately has **no delete**: journal
 * rows are immutable history inside the retention window — the caller computes
 * `expiresAt` and archival is out of band.
 */
@Dao
interface JournalDao {
    @Insert
    suspend fun insertPending(entry: JournalEntryEntity): Long

    @Query("UPDATE journal SET state = :state, error = :error WHERE id = :id")
    suspend fun markOutcome(id: String, state: JournalState, error: String?): Int

    @Query("SELECT * FROM journal WHERE state = 'PENDING'")
    suspend fun pending(): List<JournalEntryEntity>

    /**
     * Every row that is not in a verified terminal state — `PENDING` (an
     * operation was announced but never concluded) and `FAILED` (the operation
     * reported a failure, but a mid-batch delegate failure marks *every* row of
     * the call, including items the delegate physically moved before failing).
     * Startup reconciliation (AC-5) inspects both kinds.
     */
    @Query("SELECT * FROM journal WHERE state IN ('PENDING', 'FAILED')")
    suspend fun unresolved(): List<JournalEntryEntity>

    @Query("SELECT * FROM journal ORDER BY createdAt DESC LIMIT :limit OFFSET :offset")
    suspend fun page(limit: Int, offset: Int): List<JournalEntryEntity>

    @Query("SELECT * FROM journal WHERE batchId = :batchId")
    suspend fun byBatch(batchId: String): List<JournalEntryEntity>
}
