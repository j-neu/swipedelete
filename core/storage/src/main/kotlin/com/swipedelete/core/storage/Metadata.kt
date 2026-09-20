package com.swipedelete.core.storage

/** Resolved metadata for a media item. Expensive fields are nullable. */
data class Metadata(
    val sizeBytes: Long,
    val mime: String,
    val lastModified: Long,
    val sha256: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
)
