package com.swipedelete.core.storage

import com.swipedelete.core.model.BatchId
import com.swipedelete.core.model.ItemId
import com.swipedelete.core.model.JournalEntry

/** A per-item failure that did not abort a batch operation. */
data class Failure(val itemId: ItemId, val reason: String, val cause: String? = null)

sealed interface QuarantineResult {
    data class Quarantined(val batch: BatchId, val entries: List<JournalEntry>) : QuarantineResult
    data class InsufficientSpace(val neededBytes: Long, val freeBytes: Long) : QuarantineResult
    data class Partial(val entries: List<JournalEntry>, val failures: List<Failure>) : QuarantineResult
}
