package com.swipedelete.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.swipedelete.core.database.entity.StorageSnapshotEntity

@Dao
interface SnapshotDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(snapshot: StorageSnapshotEntity)

    @Query("SELECT * FROM storageSnapshots ORDER BY takenAt DESC")
    suspend fun recent(): List<StorageSnapshotEntity>

    @Query("SELECT * FROM storageSnapshots WHERE volume = :volume ORDER BY takenAt DESC LIMIT :limit")
    suspend fun forVolume(volume: String, limit: Int): List<StorageSnapshotEntity>
}
