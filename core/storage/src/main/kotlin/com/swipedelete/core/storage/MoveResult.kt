package com.swipedelete.core.storage

import com.swipedelete.core.model.MediaItem

sealed interface MoveResult {
    /** The item landed at [to] under its original filename. */
    data class Moved(val to: MediaItem) : MoveResult
    /** The original filename was taken; the item landed at [resolvedTo] with a ` (n)` suffix. */
    data class NameCollision(val resolvedTo: MediaItem) : MoveResult
    data class Failed(val reason: String) : MoveResult
}
