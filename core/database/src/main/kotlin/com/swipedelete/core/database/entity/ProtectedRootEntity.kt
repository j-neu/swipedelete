package com.swipedelete.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.swipedelete.core.model.ProtectedRootKind

@Entity(tableName = "protectedRoots")
data class ProtectedRootEntity(
    @PrimaryKey val pattern: String,
    @ColumnInfo(name = "kind") val kind: ProtectedRootKind,
    @ColumnInfo(name = "unlockedAt") val unlockedAt: Long? = null,
    @ColumnInfo(name = "unlockMethod") val unlockMethod: String? = null,
)
