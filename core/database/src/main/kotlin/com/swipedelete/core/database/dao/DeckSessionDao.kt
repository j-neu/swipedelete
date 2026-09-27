package com.swipedelete.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.swipedelete.core.database.entity.DeckSessionEntity
import com.swipedelete.core.model.DeckStatus

@Dao
interface DeckSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: DeckSessionEntity)

    @Query("SELECT * FROM deckSessions WHERE id = :id")
    suspend fun byId(id: String): DeckSessionEntity?

    @Query("SELECT * FROM deckSessions WHERE status = 'ACTIVE' ORDER BY createdAt DESC")
    suspend fun active(): List<DeckSessionEntity>

    @Query("SELECT * FROM deckSessions ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<DeckSessionEntity>

    /**
     * M1-T12: persist the resume position after a decision. The cursor and the
     * encoded session state are written in one statement so a crash can never
     * leave the cursor pointing at a state that was never saved. Returns the
     * number of rows updated (0 for an unknown session).
     */
    @Query("UPDATE deckSessions SET cursor = :cursor, stateData = :stateData WHERE id = :id")
    suspend fun persistProgress(id: String, cursor: Int, stateData: String?): Int

    /** Marks a session `COMPLETED`/`ABANDONED` when the run ends. */
    @Query("UPDATE deckSessions SET status = :status WHERE id = :id")
    suspend fun markStatus(id: String, status: DeckStatus): Int

    /** The newest `ACTIVE` session, i.e. the one a launch offers to resume. */
    @Query("SELECT * FROM deckSessions WHERE status = 'ACTIVE' ORDER BY createdAt DESC LIMIT 1")
    suspend fun latestActive(): DeckSessionEntity?

    @Query("DELETE FROM deckSessions WHERE id = :id")
    suspend fun delete(id: String)
}
