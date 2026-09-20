package com.swipedelete.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(tableName = "storageSnapshots", primaryKeys = ["takenAt", "volume"])
data class StorageSnapshotEntity(
    @ColumnInfo(name = "takenAt") val takenAt: Long,
    @ColumnInfo(name = "volume") val volume: String,
    @ColumnInfo(name = "totalBytes") val totalBytes: Long,
    @ColumnInfo(name = "freeBytes") val freeBytes: Long,
    @ColumnInfo(name = "breakdownJson") val breakdownJson: String,
)
