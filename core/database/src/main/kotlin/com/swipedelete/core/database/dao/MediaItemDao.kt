package com.swipedelete.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.swipedelete.core.database.entity.MediaItemEntity

@Dao
interface MediaItemDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: MediaItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<MediaItemEntity>)

    @Query("SELECT * FROM mediaItems WHERE id = :id")
    suspend fun byId(id: String): MediaItemEntity?

    @Query("SELECT * FROM mediaItems WHERE sourceId = :sourceId")
    suspend fun bySource(sourceId: String): List<MediaItemEntity>

    @Query("SELECT COUNT(*) FROM mediaItems")
    suspend fun count(): Long

    @Query("DELETE FROM mediaItems WHERE sourceId = :sourceId")
    suspend fun deleteBySource(sourceId: String)
}
