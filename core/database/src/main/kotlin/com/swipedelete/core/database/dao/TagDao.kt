package com.swipedelete.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.swipedelete.core.database.entity.TagEntity

/** Slot tags (SPEC 5.3): a TAG-only slot records the tag here and never touches the file. */
@Dao
interface TagDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(tag: TagEntity)

    @Query("SELECT * FROM tags WHERE itemId = :itemId ORDER BY slotId ASC")
    suspend fun forItem(itemId: String): List<TagEntity>

    @Query("DELETE FROM tags WHERE itemId = :itemId AND slotId = :slotId")
    suspend fun delete(itemId: String, slotId: Int)
}
