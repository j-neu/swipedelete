package com.swipedelete.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.swipedelete.core.model.SourceKind
import com.swipedelete.core.model.SourceRef

@Entity(tableName = "sourceRefs")
data class SourceRefEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "treeUri") val treeUri: String,
    @ColumnInfo(name = "displayName") val displayName: String,
    @ColumnInfo(name = "access") val access: SourceRef.Access,
    @ColumnInfo(name = "kind") val kind: SourceKind,
    @ColumnInfo(name = "itemCount") val itemCount: Long? = null,
    @ColumnInfo(name = "totalBytes") val totalBytes: Long? = null,
    @ColumnInfo(name = "grantedAt") val grantedAt: Long,
)
