package com.swipedelete.feature.deck.session

import com.swipedelete.core.database.dao.DeckSessionDao
import com.swipedelete.core.database.entity.DeckSessionEntity
import com.swipedelete.core.model.BatchId
import com.swipedelete.core.model.DeckStatus
import com.swipedelete.core.model.MediaItem
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
    val restored = mutableListOf<SessionId>()

    override suspend fun quarantine(items: List<MediaItem>, batch: BatchId): QuarantineOutcome {
        quarantined += items
        quarantinedBatches += batch
        return allMoved(items)
    }

    override suspend fun restoreSession(session: SessionId): Int {
        restored += session
        return 1
    }
}

/** The outcome of a quarantine in which every item moved. */
internal fun allMoved(items: List<MediaItem>): QuarantineOutcome =
    QuarantineOutcome(moved = items.mapTo(linkedSetOf()) { it.id }, failures = emptyList())
