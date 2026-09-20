package com.swipedelete.core.storage

import com.swipedelete.core.common.Clock
import com.swipedelete.core.database.dao.JournalDao
import com.swipedelete.core.database.entity.JournalEntryEntity
import com.swipedelete.core.model.BatchId
import com.swipedelete.core.model.JournalEntry
import com.swipedelete.core.model.JournalState
import com.swipedelete.core.model.MediaItem
import com.swipedelete.core.model.OpKind
import java.util.UUID

/**
 * Write-ahead journal decorator (AC-1): no file operation occurs without a
 * prior committed journal entry.
 *
 * Every mutating call commits a `PENDING` [JournalEntryEntity] **before** the
 * delegate performs the operation and moves the row to a terminal state
 * (`DONE` on a normal return, `FAILED` when the delegate throws) afterwards. A
 * `FAILED` row records the thrown error message and the exception is rethrown,
 * so the caller still observes the failure.
 *
 * A multi-item operation ([quarantine], [deletePermanently]) writes one row per
 * item, in list order, so a batch that aborts with an exception fails every
 * item's row individually and each row names exactly the item it covers.
 *
 * Read-only calls ([StorageGateway.sources], [StorageGateway.enumerate],
 * [StorageGateway.metadata], [StorageGateway.openStream],
 * [StorageGateway.capacity], [StorageGateway.watch]) pass straight through to
 * the delegate and never touch the journal.
 *
 * Sits between the protected-roots decorator and the concrete gateway in the
 * stack: `ProtectedRoots → Journaling → Quarantine → Concrete`.
 */
class JournalingStorageGateway(
    private val delegate: StorageGateway,
    private val journal: JournalDao,
    private val clock: Clock,
    private val retentionMillis: Long = DEFAULT_RETENTION_MILLIS,
) : StorageGateway by delegate {

    override suspend fun move(item: MediaItem, destination: Destination): MoveResult =
        journaled(
            listOf(
                entry(
                    opKind = OpKind.MOVE,
                    batchId = newBatchId(),
                    itemId = item.id.value,
                    fromUri = item.uri,
                    toUri = destinationUri(item, destination),
                    hash = item.hash,
                    sizeBytes = item.sizeBytes,
                ),
            ),
        ) { delegate.move(item, destination) }

    override suspend fun quarantine(items: List<MediaItem>, batch: BatchId): QuarantineResult =
        journaled(
            items.map { item ->
                entry(
                    opKind = OpKind.QUARANTINE,
                    batchId = batch.value,
                    itemId = item.id.value,
                    fromUri = item.uri,
                    toUri = null,
                    hash = item.hash,
                    sizeBytes = item.sizeBytes,
                )
            },
        ) { delegate.quarantine(items, batch) }

    override suspend fun restore(entry: JournalEntry): RestoreResult =
        journaled(
            listOf(
                entry(
                    opKind = OpKind.RESTORE,
                    batchId = newBatchId(),
                    itemId = entry.itemId.value,
                    fromUri = entry.toUri ?: entry.fromUri,
                    toUri = entry.fromUri,
                    hash = entry.hash,
                    sizeBytes = entry.sizeBytes,
                ),
            ),
        ) { delegate.restore(entry) }

    override suspend fun deletePermanently(items: List<MediaItem>): DeleteResult {
        val batchId = newBatchId()
        return journaled(
            items.map { item ->
                entry(
                    opKind = OpKind.DELETE_PERMANENT,
                    batchId = batchId,
                    itemId = item.id.value,
                    fromUri = item.uri,
                    toUri = null,
                    hash = item.hash,
                    sizeBytes = item.sizeBytes,
                )
            },
        ) { delegate.deletePermanently(items) }
    }

    /**
     * Commits every [entries] row as `PENDING`, runs [operation], then writes the
     * terminal outcome. An exception fails every row for this call (each row
     * naming its own item) and is rethrown unchanged.
     */
    private suspend fun <T> journaled(
        entries: List<JournalEntryEntity>,
        operation: suspend () -> T,
    ): T {
        entries.forEach { journal.insertPending(it) }
        val result = try {
            operation()
        } catch (error: Exception) {
            mark(entries, JournalState.FAILED, error.message)
            throw error
        }
        mark(entries, JournalState.DONE, null)
        return result
    }

    private suspend fun mark(
        entries: List<JournalEntryEntity>,
        state: JournalState,
        error: String?,
    ) {
        entries.forEach { journal.markOutcome(it.id, state, error) }
    }

    private fun entry(
        opKind: OpKind,
        batchId: String,
        itemId: String,
        fromUri: String,
        toUri: String?,
        hash: String?,
        sizeBytes: Long,
    ): JournalEntryEntity {
        val now = clock.nowMillis()
        return JournalEntryEntity(
            id = UUID.randomUUID().toString(),
            batchId = batchId,
            sessionId = null,
            itemId = itemId,
            opKind = opKind,
            fromUri = fromUri,
            toUri = toUri,
            hash = hash,
            sizeBytes = sizeBytes,
            state = JournalState.PENDING,
            createdAt = now,
            expiresAt = now + retentionMillis,
            error = null,
        )
    }

    private fun newBatchId(): String = UUID.randomUUID().toString()

    /** Best-effort intended target for [move]; the delegate may disambiguate the name. */
    private fun destinationUri(item: MediaItem, destination: Destination): String {
        val directory = destination.relativePath
            ?.trim('/')
            ?.takeIf { it.isNotEmpty() }
            ?.let { "${destination.source.treeUri}/$it" }
            ?: destination.source.treeUri
        val name = item.path?.substringAfterLast('/') ?: item.uri.substringAfterLast('/')
        return "$directory/$name"
    }

    companion object {
        /** Journal retention window when the caller does not supply one. */
        const val DEFAULT_RETENTION_MILLIS: Long = 30L * 24 * 60 * 60 * 1000
    }
}
