package com.swipedelete.core.model

data class MediaItem(
    val id: ItemId, val uri: String, val path: String?, val relativePath: String?,
    val mediaStoreId: Long?, val mime: String, val sizeBytes: Long,
    val width: Int?, val height: Int?, val durationMs: Long?,
    val dateAdded: Long, val dateModified: Long, val bucket: String?,
    val sourceId: SourceId, val hash: String? = null, val phash: Long? = null,
    val protectedRoot: Boolean = false,
)
