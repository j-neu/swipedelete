package com.swipedelete.core.storage.quarantine

import com.swipedelete.core.common.Clock
import com.swipedelete.core.database.dao.JournalDao
import com.swipedelete.core.database.entity.JournalEntryEntity
import com.swipedelete.core.model.BatchId
import com.swipedelete.core.model.JournalState
import com.swipedelete.core.model.OpKind
import com.swipedelete.core.storage.RestoreResult
import com.swipedelete.core.storage.StorageGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

sealed interface EmptyOutcome {
    /** `allowPermanent` was false: nothing was touched. */
    data object Refused : EmptyOutcome

    data class Emptied(val count: Int, val bytes: Long) : EmptyOutcome

    /** Some files could not be removed; they are still held and still restorable. */
    data class Partial(val emptied: Int, val failed: Int) : EmptyOutcome
}

data class RestoreAllOutcome(val restored: Int, val kept: Int)

enum class HistoryStatus { Restorable, Restored, NoLongerRestorable, Other }

/** One journal row with what is known about its bytes now. [held] is set only for [HistoryStatus.Restorable]. */
data class HistoryItem(val row: JournalEntryEntity, val status: HistoryStatus, val held: QuarantineEntry?)

/**
 * The dashboard's single door into quarantine: list it, restore it, empty it.
 *
 * Reading goes to the store; restoring goes through [gateway], the journaled
 * decorator stack; the one permanent delete, [emptyAll], waits on [gate]
 * (startup recovery), refuses unless `allowPermanent` is true, and writes a
 * `DELETE_PERMANENT` row for every file before removing anything (AC-1).
 * No swipe or one-tap path reaches it.
 */
class QuarantineAdmin(
    private val store: QuarantineStore,
    private val gateway: StorageGateway,
    private val journal: JournalDao,
    private val clock: Clock,
    private val retentionMillis: Long = 30L * 24 * 60 * 60 * 1000,
    private val gate: suspend () -> Unit = {},
) {

    suspend fun contents(): List<QuarantineEntry> = withContext(Dispatchers.IO) { store.scan() }

    /** Every journal row, newest first, each classified against what is quarantined now. */
    suspend fun history(): List<HistoryItem> {
        val rows = ArrayList<JournalEntryEntity>()
        while (true) {
            val page = journal.page(PAGE, rows.size)
            rows += page
            if (page.size < PAGE) break
        }
        val held = contents()
        val restoredAt = rows.filter { it.opKind == OpKind.RESTORE && it.state == JournalState.DONE }
            .groupBy({ it.itemId }, { it.createdAt })
        return rows.map { row ->
            if (row.opKind != OpKind.QUARANTINE || row.state != JournalState.DONE) {
                return@map HistoryItem(row, HistoryStatus.Other, null)
            }
            val copy = held.firstOrNull { it.sidecar.batchId == row.batchId && it.sidecar.originalUri == row.fromUri }
            when {
                restoredAt[row.itemId]?.any { it >= row.createdAt } == true -> HistoryItem(row, HistoryStatus.Restored, null)
                copy != null -> HistoryItem(row, HistoryStatus.Restorable, copy)
                else -> HistoryItem(row, HistoryStatus.NoLongerRestorable, null)
            }
        }
    }

    /**
     * Restores one item. Plain restore goes through the journaled gateway; 'keep both'
     * (restore beside an occupant under a new name) writes its own `RESTORE` row
     * first, because the frozen gateway has no such verb.
     */
    suspend fun restore(item: HistoryItem, keepBoth: Boolean = false): RestoreResult {
        gate()
        val held = item.held ?: return RestoreResult.Conflict(item.row.fromUri, "no_quarantine_copy")
        if (!keepBoth) return gateway.restore(held.entry)
        val now = clock.nowMillis()
        val row = JournalEntryEntity(
            id = UUID.randomUUID().toString(), batchId = UUID.randomUUID().toString(), sessionId = null,
            itemId = held.entry.itemId.value, opKind = OpKind.RESTORE, fromUri = held.path, toUri = held.entry.fromUri,
            hash = held.sidecar.sha256, sizeBytes = held.sidecar.sizeBytes, state = JournalState.PENDING,
            createdAt = now, expiresAt = now + retentionMillis, error = null,
        )
        journal.insertPending(row)
        val result = try {
            store.restore(held.entry, keepBoth = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            journal.markOutcome(row.id, JournalState.FAILED, error.message)
            throw error
        }
        if (result is RestoreResult.Restored) {
            journal.markOutcome(row.id, JournalState.DONE, null)
        } else {
            journal.markOutcome(row.id, JournalState.FAILED, result.toString())
        }
        return result
    }

    suspend fun restoreAll(): RestoreAllOutcome {
        gate()
        var restored = 0
        var kept = 0
        contents().forEach { held ->
            if (gateway.restore(held.entry) is RestoreResult.Restored) restored++ else kept++
        }
        return RestoreAllOutcome(restored, kept)
    }

    suspend fun emptyAll(allowPermanent: Boolean): EmptyOutcome {
        gate()
        if (!allowPermanent) return EmptyOutcome.Refused
        val held = contents()
        if (held.isEmpty()) return EmptyOutcome.Emptied(0, 0L)

        val purgeBatch = UUID.randomUUID().toString()
        val rows = held.map { rowFor(it, purgeBatch) }
        val inserted = ArrayList<JournalEntryEntity>(rows.size)
        try {
            rows.forEach {
                journal.insertPending(it)
                inserted += it
            }
            withContext(Dispatchers.IO) {
                held.map { it.sidecar.batchId }.distinct().forEach { store.empty(BatchId(it), allowPermanent = true) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            inserted.forEach { journal.markOutcome(it.id, JournalState.FAILED, error.message) }
            throw error
        }

        var emptied = 0
        var failed = 0
        var bytes = 0L
        held.forEachIndexed { i, entry ->
            if (File(entry.path).exists()) {
                failed++
                journal.markOutcome(rows[i].id, JournalState.FAILED, "still_present")
            } else {
                emptied++
                bytes += entry.sidecar.sizeBytes
                journal.markOutcome(rows[i].id, JournalState.DONE, null)
            }
        }
        return if (failed == 0) EmptyOutcome.Emptied(emptied, bytes) else EmptyOutcome.Partial(emptied, failed)
    }

    private companion object {
        const val PAGE = 200
    }

    private fun rowFor(held: QuarantineEntry, batchId: String): JournalEntryEntity {
        val now = clock.nowMillis()
        return JournalEntryEntity(
            id = UUID.randomUUID().toString(),
            batchId = batchId,
            sessionId = null,
            itemId = held.entry.itemId.value,
            opKind = OpKind.DELETE_PERMANENT,
            fromUri = held.path,
            toUri = null,
            hash = held.sidecar.sha256,
            sizeBytes = held.sidecar.sizeBytes,
            state = JournalState.PENDING,
            createdAt = now,
            expiresAt = now + retentionMillis,
            error = null,
        )
    }
}
