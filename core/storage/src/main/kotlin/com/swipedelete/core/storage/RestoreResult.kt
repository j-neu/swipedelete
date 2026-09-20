package com.swipedelete.core.storage

sealed interface RestoreResult {
    /** The quarantined item was placed back at [to]. */
    data class Restored(val to: String) : RestoreResult
    /** Something else now occupies [originalPath]; nothing was touched. */
    data class Conflict(val originalPath: String, val occupiedBy: String) : RestoreResult
    data class HashMismatch(val expected: String, val actual: String) : RestoreResult
}
