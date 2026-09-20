package com.swipedelete.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.swipedelete.core.database.entity.DeckSessionEntity

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

    @Query("DELETE FROM deckSessions WHERE id = :id")
    suspend fun delete(id: String)
}
