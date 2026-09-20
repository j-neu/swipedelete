package com.swipedelete.core.storage

import com.swipedelete.core.common.Clock
import com.swipedelete.core.database.dao.JournalDao
import com.swipedelete.core.database.entity.JournalEntryEntity
import com.swipedelete.core.model.BatchId
import com.swipedelete.core.model.JournalEntry
import com.swipedelete.core.model.JournalState
import com.swipedelete.core.model.MediaItem
import com.swipedelete.core.model.OpKind
import kotlinx.coroutines.CancellationException
import java.util.UUID

/**
 * Write-ahead journal decorator (AC-1): no file operation occurs without a
 * prior committed journal entry.
 *
 * Every mutating call commits a `PENDING` [JournalEntryEntity] **before** the
 * delegate performs the operation and moves each row to a terminal state
 * afterwards. The terminal state is derived from the delegate's **result**, not
 * merely from whether it threw:
 *
 * - `quarantine`: `Quarantined` → `DONE`; `Partial` → `DONE` for the itemIds in
 *   `entries` and `FAILED` (the matching [Failure.reason]) for the itemIds in
 *   `failures`; `InsufficientSpace` → `FAILED` (`insufficient_space`).
 * - `deletePermanently`: `Deleted` → `DONE`; `Partial` → `FAILED` for the itemIds
 *   in `failures`, `DONE` for the rest.
 * - `move`: `Moved` / `NameCollision` → `DONE`; `Failed` → `FAILED` (the reason).
 * - `restore`: `Restored` → `DONE`; `Conflict` / `HashMismatch` → `FAILED` with a
 *   reason naming which one.
 *
 * A thrown delegate exception fails every row created for the call and is
 * rethrown. A [CancellationException] is never treated as a failure: it
 * propagates untouched so a cancelled scope does not rewrite the journal.
 *
 * A multi-item operation ([quarantine], [deletePermanently]) writes one row per
 * item, in list order.
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
            entries = listOf(
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
            operation = { delegate.move(item, destination) },
        ) { result ->
            listOf(
                when (result) {
                    is MoveResult.Moved, is MoveResult.NameCollision -> JournalOutcome.DONE
                    is MoveResult.Failed -> JournalOutcome.failed(result.reason)
                },
            )
        }

    override suspend fun quarantine(items: List<MediaItem>, batch: BatchId): QuarantineResult {
        val rows = items.map { item ->
            entry(
                opKind = OpKind.QUARANTINE,
                batchId = batch.value,
                itemId = item.id.value,
                fromUri = item.uri,
                toUri = null,
                hash = item.hash,
                sizeBytes = item.sizeBytes,
            )
        }
        return journaled(rows, operation = { delegate.quarantine(items, batch) }) { result ->
            when (result) {
                is QuarantineResult.Quarantined -> rows.map { JournalOutcome.DONE }
                is QuarantineResult.Partial -> {
                    val reasons = result.failures.associate { it.itemId.value to it.reason }
                    val moved = result.entries.mapTo(mutableSetOf()) { it.itemId.value }
                    rows.map { row ->
                        when {
                            reasons.containsKey(row.itemId) -> JournalOutcome.failed(reasons.getValue(row.itemId))
                            row.itemId in moved -> JournalOutcome.DONE
                            else -> JournalOutcome.failed("no outcome reported by the delegate")
                        }
                    }
                }
                is QuarantineResult.InsufficientSpace ->
                    rows.map { JournalOutcome.failed("insufficient_space") }
            }
        }
    }

    override suspend fun restore(entry: JournalEntry): RestoreResult =
        journaled(
            entries = listOf(
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
            operation = { delegate.restore(entry) },
        ) { result ->
            listOf(
                when (result) {
                    is RestoreResult.Restored -> JournalOutcome.DONE
                    is RestoreResult.Conflict ->
                        JournalOutcome.failed("conflict: ${result.originalPath} occupied by ${result.occupiedBy}")
                    is RestoreResult.HashMismatch ->
                        JournalOutcome.failed("hash_mismatch: expected ${result.expected}, actual ${result.actual}")
                },
            )
        }

    override suspend fun deletePermanently(items: List<MediaItem>): DeleteResult {
        val batchId = newBatchId()
        val rows = items.map { item ->
            entry(
                opKind = OpKind.DELETE_PERMANENT,
                batchId = batchId,
                itemId = item.id.value,
                fromUri = item.uri,
                toUri = null,
                hash = item.hash,
                sizeBytes = item.sizeBytes,
            )
        }
        return journaled(rows, operation = { delegate.deletePermanently(items) }) { result ->
            when (result) {
                is DeleteResult.Deleted -> rows.map { JournalOutcome.DONE }
                is DeleteResult.Partial -> {
                    val reasons = result.failures.associate { it.itemId.value to it.reason }
                    rows.map { row ->
                        val reason = reasons[row.itemId]
                        if (reason != null) JournalOutcome.failed(reason) else JournalOutcome.DONE
                    }
                }
            }
        }
    }

    /**
     * The single write-ahead sequence: insert every [entries] row as `PENDING`,
     * run [operation], then map its result to one terminal [JournalOutcome] per
     * row (in the same order) and commit each.
     *
     * The insert loop is inside the guarded region: if an insert fails after
     * earlier rows were committed, those rows are failed before the error is
     * rethrown, so no row is left `PENDING` by this call.
     */
    private suspend fun <T> journaled(
        entries: List<JournalEntryEntity>,
        operation: suspend () -> T,
        outcome: (T) -> List<JournalOutcome>,
    ): T {
        val inserted = ArrayList<JournalEntryEntity>(entries.size)
        val result: T
        try {
            entries.forEach { row ->
                journal.insertPending(row)
                inserted += row
            }
            result = operation()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            markAll(inserted, JournalOutcome.failed(error.message))
            throw error
        }

        val outcomes = outcome(result)
        require(outcomes.size == entries.size) {
            "outcome mapping produced ${outcomes.size} results for ${entries.size} journal rows"
        }
        entries.forEachIndexed { index, row -> mark(row, outcomes[index]) }
        return result
    }

    private suspend fun markAll(rows: List<JournalEntryEntity>, outcome: JournalOutcome) {
        rows.forEach { row -> mark(row, outcome) }
    }

    /**
     * Marks one row terminal and verifies the DAO actually updated it. A
     * `markOutcome` that updates zero rows would leave a `PENDING` row behind,
     * which the reconciler reads as an interrupted operation, so a mismatch is
     * surfaced loudly rather than swallowed.
     *
     * `check` (an `IllegalStateException`) is used because a row count other
     * than 1 for a primary-key update is a programming/schema error, not an
     * expected I/O failure.
     */
    private suspend fun mark(row: JournalEntryEntity, outcome: JournalOutcome) {
        val updated = journal.markOutcome(row.id, outcome.state, outcome.error)
        check(updated == 1) {
            "journal.markOutcome(${row.id}) updated $updated rows (expected 1): " +
                "a PENDING row would be left behind and misread as an interrupted operation"
        }
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

    private data class JournalOutcome(val state: JournalState, val error: String?) {
        companion object {
            val DONE = JournalOutcome(JournalState.DONE, null)

            fun failed(reason: String?) = JournalOutcome(JournalState.FAILED, reason)
        }
    }

    companion object {
        /** Journal retention window when the caller does not supply one. */
        const val DEFAULT_RETENTION_MILLIS: Long = 30L * 24 * 60 * 60 * 1000
    }
}
