package com.swipedelete.feature.deck.session

import com.swipedelete.core.model.BatchId
import com.swipedelete.core.model.ItemId
import com.swipedelete.core.model.JournalEntry
import com.swipedelete.core.model.MediaItem
import com.swipedelete.core.storage.Failure
import com.swipedelete.core.storage.QuarantineResult
import com.swipedelete.core.storage.RestoreResult
import com.swipedelete.core.storage.StorageGateway
import com.swipedelete.feature.deck.domain.staleReason as staleReasonFor

/**
 * The production [SessionMutations] over the journaled [StorageGateway].
 *
 * [staleReason] is what makes AC-8 real in the shipped app: before an item is
 * destroyed, its *current* metadata is re-read through the gateway and compared
 * with the frozen snapshot. A changed (or vanished) item returns a user-visible
 * reason, so the session aborts it instead of deleting the wrong bytes.
 *
 * [restore] returns only the items the gateway reports as restored; a conflict
 * or a hash mismatch leaves the item in quarantine and out of the result.
 */
class GatewaySessionMutations(
    private val gateway: StorageGateway,
) : SessionMutations {

    override suspend fun quarantine(items: List<MediaItem>, batch: BatchId): QuarantineOutcome =
        when (val result = gateway.quarantine(items, batch)) {
            is QuarantineResult.Quarantined -> QuarantineOutcome(result.entries.movedIds(), emptyList(), result.entries)
            is QuarantineResult.Partial -> QuarantineOutcome(result.entries.movedIds(), result.failures, result.entries)
            is QuarantineResult.InsufficientSpace ->
                QuarantineOutcome(emptySet(), items.map { Failure(it.id, INSUFFICIENT_SPACE) })
        }

    private fun List<JournalEntry>.movedIds(): Set<ItemId> = mapTo(linkedSetOf()) { it.itemId }

    override suspend fun restore(entries: List<JournalEntry>): Set<ItemId> =
        entries.filter { gateway.restore(it) is RestoreResult.Restored }.mapTo(linkedSetOf()) { it.itemId }

    override suspend fun staleReason(item: MediaItem): String? {
        val actual = try {
            gateway.metadata(item)
        } catch (error: Exception) {
            return "No longer readable: ${error.message ?: "the item could not be read"}"
        }
        return staleReasonFor(item, actual)
    }

    private companion object {
        const val INSUFFICIENT_SPACE = "insufficient_space"
    }
}
