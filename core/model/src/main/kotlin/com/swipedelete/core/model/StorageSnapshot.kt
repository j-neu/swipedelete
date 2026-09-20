package com.swipedelete.core.model

data class StorageSnapshot(
    val takenAt: Long, val volume: String, val totalBytes: Long,
    val freeBytes: Long, val breakdownJson: String,
)
