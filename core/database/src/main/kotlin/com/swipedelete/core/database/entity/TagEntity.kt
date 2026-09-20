package com.swipedelete.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(tableName = "tags", primaryKeys = ["itemId", "slotId"])
data class TagEntity(
    @ColumnInfo(name = "itemId") val itemId: String,
    @ColumnInfo(name = "slotId") val slotId: Int,
    @ColumnInfo(name = "taggedAt") val taggedAt: Long,
)
