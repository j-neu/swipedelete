package com.swipedelete.core.storage

import com.swipedelete.core.database.dao.JournalDao
import com.swipedelete.core.database.entity.JournalEntryEntity
import com.swipedelete.core.model.BatchId
import com.swipedelete.core.model.ItemId
import com.swipedelete.core.model.JournalEntry
import com.swipedelete.core.model.JournalOutcome
import com.swipedelete.core.model.JournalState
import com.swipedelete.core.model.OpKind
import com.swipedelete.core.model.RecoveryReport
import com.swipedelete.core.model.Resolved
import com.swipedelete.core.model.SessionId
import com.swipedelete.core.storage.quarantine.QuarantineEntry
import com.swipedelete.core.storage.quarantine.QuarantineStore

/**
 * AC-5: startup reconciliation. A process death at any point must leave no item
 * in an unknown state, and reconciliation resolves every unresolved record.
 *
 * Reconciliation is a **classification**, never a repair (spec §6.2: the journal
 * is write-ahead and immutable). It reads the journal, the quarantine sidecars
 * and one existence probe, then writes **only** the journal rows' state via
 * [JournalDao.markOutcome]. It never deletes, moves or rewrites a user file, it
 * never deletes or edits a journal row, and it never touches a quarantine
 * sidecar — a row it cannot classify is returned in
 * [RecoveryReport.unknown] and left exactly as it was.
 *
 * **Unresolved rows are `PENDING` *and* `FAILED`.** A mid-batch delegate failure
 * marks *every* row of the call `FAILED`, including items the delegate
 * physically moved before failing, so a `PENDING`-only pass would strand those
 * items: the row would claim failure while the bytes sit in quarantine.
 *
 * **Correlation rule.** [JournalingStorageGateway] generates its own entry ids
 * while [QuarantineStore] derives its entry id from the batch and item, so ids
 * never match. A quarantined file is correlated with a row by
 * **`batchId` + `originalUri`**: the row's `batchId` and `fromUri` against the
 * sidecar's `batchId` and `originalUri` (read through [QuarantineStore.scan],
 * which already skips missing or unparseable sidecars).
 *
 * **Constructor deviation.** The brief specified a [StorageGateway] parameter
 * and "destination existence" resolved through it, but [StorageGateway] exposes
 * no existence operation and the journal rows written for a quarantine carry
 * `toUri = null`. Destination existence is therefore supplied by the caller as
 * [exists], a probe over a URI or path string; the app layer wires it in
 * `SwipeDeleteApplication` (Task 24). Tests back it with
 * `FakeStorageGateway.fileExists`.
 *
 * @param journal the write-ahead journal queried for unresolved rows.
 * @param quarantine the app-managed quarantine, scanned for sidecars.
 * @param exists "does this URI/path currently hold a file?".
 *
 * [reconcile] performs blocking journal and directory I/O, so callers must
 * invoke it on a background dispatcher (Task 24 calls it from
 * `SwipeDeleteApplication.onCreate` before any UI can mutate storage).
 */
class Reconciler(
    private val journal: JournalDao,
    private val quarantine: QuarantineStore,
    private val exists: suspend (uri: String) -> Boolean,
) {

    /**
     * Classifies and records every unresolved journal row, returning the
     * Recovery Report. Safe to call repeatedly: once a row is terminal it is no
     * longer unresolved, so a second pass reports nothing and changes nothing.
     */
    suspend fun reconcile(): RecoveryReport {
        val rows = journal.unresolved()
        if (rows.isEmpty()) return RecoveryReport(resolved = emptyList(), unknown = emptyList())

        val quarantined = quarantine.scan()
        val resolved = ArrayList<Resolved>(rows.size)
        val unknown = ArrayList<JournalEntry>()

        rows.forEach { row ->
            val outcome = classify(row, quarantined)
            if (outcome == null) {
                unknown += row.toJournalEntry()
            } else {
                val updated = journal.markOutcome(row.id, outcome.state(), reason(outcome, row.error))
                // A zero-row update means the row does not exist, which this DAO
                // cannot produce (it exposes no delete). Failing loudly beats
                // reporting a resolution that was never written: the row would
                // stay unresolved and be re-reported on the next launch.
                check(updated == 1) {
                    "journal.markOutcome(${row.id}) updated $updated rows (expected 1): " +
                        "the row would remain unresolved and be re-reported on the next launch"
                }
                resolved += Resolved(row.id, outcome)
            }
        }
        return RecoveryReport(resolved = resolved, unknown = unknown)
    }

    /**
     * The classification rules, in order. `null` means "unknown": the evidence
     * is contradictory or absent, so the row is reported rather than guessed at.
     */
    private suspend fun classify(
        row: JournalEntryEntity,
        quarantined: List<QuarantineEntry>,
    ): JournalOutcome? = when (row.opKind) {
        OpKind.QUARANTINE -> when {
            // A verified copy (and its sidecar) is in quarantine: the move
            // completed, whatever the row's stale state says.
            quarantined.any { it.correlatesWith(row) } -> JournalOutcome.DONE
            exists(row.fromUri) -> JournalOutcome.ABORTED
            else -> null
        }

        OpKind.MOVE -> {
            val destination = row.toUri
            when {
                destination != null && exists(destination) -> JournalOutcome.DONE
                exists(row.fromUri) -> JournalOutcome.ABORTED
                else -> null
            }
        }

        OpKind.RESTORE -> when {
            exists(row.toUri ?: row.fromUri) -> JournalOutcome.DONE
            quarantined.any { it.correlatesWith(row) } -> JournalOutcome.ABORTED
            else -> null
        }

        // Permanent deletion leaves nothing to inspect: the source is either
        // still there (the delete never happened) or gone (it did).
        OpKind.DELETE_PERMANENT ->
            if (exists(row.fromUri)) JournalOutcome.ABORTED else JournalOutcome.DONE

        // COMPRESS and any future op kind have no rule yet.
        else -> null
    }

    /**
     * The correlation rule: a quarantine sidecar belongs to [row] when both the
     * batch and the original URI match. Journal ids are generated independently
     * by the journaling decorator and the quarantine store, so ids are unusable.
     */
    private fun QuarantineEntry.correlatesWith(row: JournalEntryEntity): Boolean =
        sidecar.batchId == row.batchId && sidecar.originalUri == row.fromUri

    private fun JournalOutcome.state(): JournalState = when (this) {
        JournalOutcome.ABORTED -> JournalState.ABORTED
        JournalOutcome.DONE -> JournalState.DONE
    }

    /**
     * The reason recorded with the verdict. The row's prior error (a `FAILED`
     * row always has one) is preserved in parentheses: reconciliation must not
     * destroy the evidence of why the operation failed in the first place.
     */
    private fun reason(outcome: JournalOutcome, priorError: String?): String {
        val base = when (outcome) {
            JournalOutcome.DONE -> REASON_DONE
            JournalOutcome.ABORTED -> REASON_ABORTED
        }
        return if (priorError.isNullOrBlank()) base else "$base ($priorError)"
    }

    /** The `:core:model` view of an unresolved row, for the Recovery Report. */
    private fun JournalEntryEntity.toJournalEntry(): JournalEntry = JournalEntry(
        id = id,
        batchId = BatchId(batchId),
        sessionId = sessionId?.let(::SessionId),
        itemId = ItemId(itemId),
        opKind = opKind,
        fromUri = fromUri,
        toUri = toUri,
        hash = hash,
        sizeBytes = sizeBytes,
        state = state,
        createdAt = createdAt,
        expiresAt = expiresAt,
        error = error,
    )

    companion object {
        /** Reason recorded when reconciliation concludes the operation completed. */
        const val REASON_DONE: String = "reconciled_done"

        /** Reason recorded when reconciliation concludes the operation never ran. */
        const val REASON_ABORTED: String = "reconciled_aborted"
    }
}
