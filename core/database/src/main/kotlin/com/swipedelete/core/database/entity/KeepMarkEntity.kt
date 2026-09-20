package com.swipedelete.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(tableName = "keepMarks", primaryKeys = ["itemId", "sessionId"])
data class KeepMarkEntity(
    @ColumnInfo(name = "itemId") val itemId: String,
    @ColumnInfo(name = "sessionId") val sessionId: String,
    @ColumnInfo(name = "markedAt") val markedAt: Long,
)
