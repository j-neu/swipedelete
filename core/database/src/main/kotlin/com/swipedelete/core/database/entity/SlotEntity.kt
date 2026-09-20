package com.swipedelete.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.swipedelete.core.model.SlotMode

@Entity(tableName = "slots")
data class SlotEntity(
    @PrimaryKey val id: Int,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "icon") val icon: String,
    @ColumnInfo(name = "colour") val colour: Long,
    @ColumnInfo(name = "destinationUri") val destinationUri: String? = null,
    @ColumnInfo(name = "mode") val mode: SlotMode,
    @ColumnInfo(name = "sortOrder") val sortOrder: Int,
)
