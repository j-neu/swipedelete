package com.swipedelete.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.swipedelete.core.database.entity.SourceRefEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceRefDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(source: SourceRefEntity)

    @Query("SELECT * FROM sourceRefs ORDER BY grantedAt ASC")
    suspend fun all(): List<SourceRefEntity>

    @Query("SELECT * FROM sourceRefs ORDER BY grantedAt ASC")
    fun observeAll(): Flow<List<SourceRefEntity>>

    @Query("SELECT * FROM sourceRefs WHERE id = :id")
    suspend fun byId(id: String): SourceRefEntity?

    @Query("SELECT COUNT(*) FROM sourceRefs")
    suspend fun count(): Long

    @Query("DELETE FROM sourceRefs WHERE id = :id")
    suspend fun delete(id: String)
}
