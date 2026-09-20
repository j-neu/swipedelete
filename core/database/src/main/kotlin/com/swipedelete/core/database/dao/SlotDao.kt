package com.swipedelete.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.swipedelete.core.database.entity.SlotEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SlotDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(slot: SlotEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(slots: List<SlotEntity>)

    @Query("SELECT * FROM slots ORDER BY sortOrder ASC")
    suspend fun all(): List<SlotEntity>

    @Query("SELECT * FROM slots ORDER BY sortOrder ASC")
    fun observeAll(): Flow<List<SlotEntity>>

    @Query("SELECT * FROM slots WHERE id = :id")
    suspend fun byId(id: Int): SlotEntity?

    @Query("DELETE FROM slots WHERE id = :id")
    suspend fun delete(id: Int)
}
