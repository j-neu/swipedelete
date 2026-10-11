package com.swipedelete.feature.deck.session

import com.swipedelete.core.database.dao.DeckSessionDao
import com.swipedelete.core.database.entity.DeckSessionEntity
import com.swipedelete.core.model.BatchId
import com.swipedelete.core.model.DeckStatus
import com.swipedelete.core.model.ItemId
import com.swipedelete.core.model.JournalEntry
import com.swipedelete.core.model.JournalState
import com.swipedelete.core.model.MediaItem
import com.swipedelete.core.model.OpKind
import com.swipedelete.core.model.SessionId

/**
 * An in-memory [DeckSessionDao] for the session tests. It is deliberately a
 * faithful stand-in for the Room DAO: every mutating call writes the whole row,
 * so a "process death" that keeps this map and drops the controller is exactly
 * the durable state the next process reads back.
 */
internal class FakeDeckSessionDao : DeckSessionDao {

    private val rows = LinkedHashMap<String, DeckSessionEntity>()

    override suspend fun upsert(session: DeckSessionEntity) {
        rows[session.id] = session
    }

    override suspend fun byId(id: String): DeckSessionEntity? = rows[id]

    override suspend fun active(): List<DeckSessionEntity> =
        rows.values.filter { it.status == DeckStatus.ACTIVE }.sortedByDescending { it.createdAt }

    override suspend fun recent(limit: Int): List<DeckSessionEntity> =
        rows.values.sortedByDescending { it.createdAt }.take(limit)

    override suspend fun delete(id: String) {
        rows.remove(id)
    }

    override suspend fun persistProgress(id: String, cursor: Int, stateData: String?): Int {
        val row = rows[id] ?: return 0
        rows[id] = row.copy(cursor = cursor, stateData = stateData)
        return 1
    }

    override suspend fun markStatus(id: String, status: DeckStatus): Int {
        val row = rows[id] ?: return 0
        rows[id] = row.copy(status = status)
        return 1
    }

    override suspend fun latestActive(): DeckSessionEntity? = active().firstOrNull()
}

/** Records every destructive call, so a test can prove one ran — or never ran. */
internal class RecordingMutations : SessionMutations {
    val quarantined = mutableListOf<List<MediaItem>>()
    val quarantinedBatches = mutableListOf<BatchId>()
    val restored = mutableListOf<List<JournalEntry>>()

    /** Which of the requested entries a restore brings back; all of them by default. */
    var restorable: (JournalEntry) -> Boolean = { true }

    override suspend fun quarantine(items: List<MediaItem>, batch: BatchId): QuarantineOutcome {
        quarantined += items
        quarantinedBatches += batch
        return allMoved(items, batch)
    }

    override suspend fun restore(entries: List<JournalEntry>): Set<ItemId> {
        restored += entries
        return entries.filter(restorable).mapTo(linkedSetOf()) { it.itemId }
    }
}

/** The outcome of a quarantine in which every item moved, with its journal entries. */
internal fun allMoved(items: List<MediaItem>, batch: BatchId = BatchId("batch")): QuarantineOutcome =
    QuarantineOutcome(
        moved = items.mapTo(linkedSetOf()) { it.id },
        failures = emptyList(),
        entries = items.map { quarantineEntry(it, batch) },
    )

internal fun quarantineEntry(item: MediaItem, batch: BatchId): JournalEntry = JournalEntry(
    id = "journal-${item.id.value}",
    batchId = batch,
    sessionId = null,
    itemId = item.id,
    opKind = OpKind.QUARANTINE,
    fromUri = item.uri,
    toUri = "quarantine/${item.id.value}",
    hash = null,
    sizeBytes = item.sizeBytes,
    state = JournalState.DONE,
    createdAt = 0L,
    expiresAt = 0L,
)
