package com.swipedelete.feature.dashboard.quarantine

/** What the quarantine screen needs from storage; implemented in `:app` over `QuarantineAdmin`. */
interface QuarantinePort {
    suspend fun held(): List<HeldItem>

    /** Returns how many files were restored. */
    suspend fun restoreAll(): Int

    /** Returns how many files were permanently deleted; 0 and nothing touched when not [allowPermanent]. */
    suspend fun emptyAll(allowPermanent: Boolean): Int
}
