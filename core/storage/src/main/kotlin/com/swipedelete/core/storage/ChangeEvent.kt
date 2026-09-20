package com.swipedelete.core.storage

import com.swipedelete.core.model.ItemId
import com.swipedelete.core.model.MediaItem

/** A change observed on a watched source. */
sealed interface ChangeEvent {
    data class Added(val item: MediaItem) : ChangeEvent
    data class Removed(val id: ItemId) : ChangeEvent
    data class Modified(val item: MediaItem) : ChangeEvent
}
