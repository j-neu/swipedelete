package com.swipedelete.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "mediaItems")
data class MediaItemEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "uri") val uri: String,
    @ColumnInfo(name = "path") val path: String? = null,
    @ColumnInfo(name = "relativePath") val relativePath: String? = null,
    @ColumnInfo(name = "mediaStoreId") val mediaStoreId: Long? = null,
    @ColumnInfo(name = "mime") val mime: String,
    @ColumnInfo(name = "sizeBytes") val sizeBytes: Long,
    @ColumnInfo(name = "width") val width: Int? = null,
    @ColumnInfo(name = "height") val height: Int? = null,
    @ColumnInfo(name = "durationMs") val durationMs: Long? = null,
    @ColumnInfo(name = "dateAdded") val dateAdded: Long,
    @ColumnInfo(name = "dateModified") val dateModified: Long,
    @ColumnInfo(name = "bucket") val bucket: String? = null,
    @ColumnInfo(name = "sourceId") val sourceId: String,
    @ColumnInfo(name = "hash") val hash: String? = null,
    @ColumnInfo(name = "phash") val phash: Long? = null,
    @ColumnInfo(name = "protectedRoot") val protectedRoot: Boolean = false,
)
