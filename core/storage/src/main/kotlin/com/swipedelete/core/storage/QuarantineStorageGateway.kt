package com.swipedelete.core.storage

import com.swipedelete.core.model.BatchId
import com.swipedelete.core.model.JournalEntry
import com.swipedelete.core.model.MediaItem
import com.swipedelete.core.storage.quarantine.QuarantineStore
import kotlinx.coroutines.CancellationException

/**
 * The Quarantine layer of the frozen decorator stack
 * `ProtectedRoots → Journaling → Quarantine → Concrete`.
 *
 * It overrides only [quarantine] and [restore]; every other call passes straight
 * through to [delegate] by interface delegation. [quarantine] runs the store's
 * space pre-flight once for the whole batch (so a shortfall moves nothing) and
 * then routes each item through the store's copy → verify → delete sequence.
 * [restore] is the store's hash-verified, never-overwriting restore (AC-17).
 */
class QuarantineStorageGateway(
    private val delegate: StorageGateway,
    private val store: QuarantineStore,
) : StorageGateway by delegate {

    override suspend fun quarantine(items: List<MediaItem>, batch: BatchId): QuarantineResult {
        store.preflight(items)?.let { return it }

        val entries = ArrayList<JournalEntry>(items.size)
        val failures = ArrayList<Failure>()
        items.forEach { item ->
            try {
                entries += store.store(item, batch)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failures += Failure(item.id, error.message ?: "quarantine_failed", error::class.simpleName)
            }
        }
        return if (failures.isEmpty()) {
            QuarantineResult.Quarantined(batch, entries)
        } else {
            QuarantineResult.Partial(entries, failures)
        }
    }

    override suspend fun restore(entry: JournalEntry): RestoreResult = store.restore(entry)
}
