package com.swipedelete.core.storage

import com.swipedelete.core.common.Clock
import com.swipedelete.core.database.dao.JournalDao
import com.swipedelete.core.database.entity.JournalEntryEntity
import com.swipedelete.core.model.BatchId
import com.swipedelete.core.model.ItemId
import com.swipedelete.core.model.JournalEntry
import com.swipedelete.core.model.JournalState
import com.swipedelete.core.model.MediaItem
import com.swipedelete.core.model.OpKind
import com.swipedelete.core.model.SourceId
import com.swipedelete.core.model.SourceKind
import com.swipedelete.core.model.SourceRef
import com.swipedelete.core.storage.fake.FakeStorageGateway
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * AC-1: no file operation occurs without a prior committed journal entry. The
 * trace of every mutating call must read `PENDING`, `OP`, then a terminal
 * `DONE`/`FAILED`, and read-only calls must never touch the journal. The
 * terminal state is derived from the delegate's result, not merely from whether
 * it threw.
 */
class JournalWriteAheadTest {

    private val trace = mutableListOf<String>()

    /** Real fake gateway with a few registered files, wrapped by the tracing double. */
    private val fake = FakeStorageGateway(listOf(item("item-1"), item("item-2"), item("item-3")))
    private val journal = RecordingJournal(trace)
    private val delegate = RecordingGateway(delegate = fake, trace = trace)
    private val gateway = JournalingStorageGateway(delegate, journal, FixedClock(1_000L))

    /** Compile-time proof that `openStream` stays non-suspend through the decorator. */
    private val openStream: (MediaItem) -> InputStream = gateway::openStream

    @Test
    fun `pending is committed before the operation runs`() = runTest {
        val result = gateway.quarantine(listOf(item("item-1")), BatchId("b1"))
        assertTrue(result is QuarantineResult.Quarantined)
        assertEquals(listOf("PENDING:item-1", "OP:item-1", "DONE:item-1"), trace)
    }

    @Test
    fun `a failing operation records FAILED after PENDING and propagates the error`() = runTest {
        delegate.failNext = "io error"
        assertFailsWith<IOException> { gateway.quarantine(listOf(item("item-1")), BatchId("b1")) }
        assertEquals(listOf("PENDING:item-1", "OP:item-1", "FAILED:item-1"), trace)
        assertEquals(JournalState.FAILED, journal.rows.single().state)
        assertEquals("io error", journal.rows.single().error)
    }

    @Test
    fun `read-only calls do not touch the journal`() = runTest {
        gateway.sources()
        gateway.metadata(item("item-1"))
        gateway.capacity()
        openStream(item("item-1")).close()
        assertTrue(trace.none { it.startsWith("PENDING") })
    }

    @Test
    fun `permanent deletion is journaled as DELETE_PERMANENT`() = runTest {
        val result = gateway.deletePermanently(listOf(item("item-1")))
        assertTrue(result is DeleteResult.Deleted)
        assertEquals(OpKind.DELETE_PERMANENT, journal.rows.single().opKind)
        assertEquals(JournalState.DONE, journal.rows.single().state)
    }

    @Test
    fun `a partial quarantine records DONE for moved items and FAILED for failed items`() = runTest {
        val result = gateway.quarantine(listOf(item("item-1"), item("ghost")), BatchId("b1"))
        assertTrue(result is QuarantineResult.Partial)
        val byItem = journal.rows.associateBy { it.itemId }
        assertEquals(JournalState.DONE, byItem.getValue("item-1").state)
        assertEquals(JournalState.FAILED, byItem.getValue("ghost").state)
        assertEquals("source not found: fake://primary/ghost", byItem.getValue("ghost").error)
    }

    @Test
    fun `a failed move records FAILED`() = runTest {
        val result = gateway.move(item("ghost"), destination())
        assertTrue(result is MoveResult.Failed)
        assertEquals(JournalState.FAILED, journal.rows.single().state)
        assertEquals(OpKind.MOVE, journal.rows.single().opKind)
        assertEquals("source not found: fake://primary/ghost", journal.rows.single().error)
    }

    @Test
    fun `a restore conflict records FAILED`() = runTest {
        val entry = JournalEntry(
            id = "j1",
            batchId = BatchId("b1"),
            sessionId = null,
            itemId = ItemId("item-1"),
            opKind = OpKind.QUARANTINE,
            fromUri = "fake://primary/item-1",
            toUri = "fake://quarantine/b1/item-1",
            hash = null,
            sizeBytes = 1024L,
            state = JournalState.DONE,
            createdAt = 0L,
            expiresAt = 0L,
        )

        val result = gateway.restore(entry)

        assertTrue(result is RestoreResult.Conflict)
        assertEquals(JournalState.FAILED, journal.rows.single().state)
        assertEquals(OpKind.RESTORE, journal.rows.single().opKind)
        assertTrue(journal.rows.single().error!!.startsWith("conflict"))
    }

    @Test
    fun `a multi-item success writes one row per item in list order`() = runTest {
        val result = gateway.quarantine(listOf(item("item-1"), item("item-2")), BatchId("b1"))
        assertTrue(result is QuarantineResult.Quarantined)
        assertEquals(listOf("item-1", "item-2"), journal.rows.map { it.itemId })
        assertEquals(listOf(JournalState.DONE, JournalState.DONE), journal.rows.map { it.state })
        assertEquals(
            listOf(
                "PENDING:item-1", "PENDING:item-2",
                "OP:item-1", "OP:item-2",
                "DONE:item-1", "DONE:item-2",
            ),
            trace,
        )
    }
}

private class RecordingJournal(private val trace: MutableList<String>) : JournalDao {
    val rows = mutableListOf<JournalEntryEntity>()
    private val itemByEntry = mutableMapOf<String, String>()

    override suspend fun insertPending(entry: JournalEntryEntity): Long {
        trace += "PENDING:${entry.itemId}"
        itemByEntry[entry.id] = entry.itemId
        rows += entry
        return rows.size.toLong()
    }

    override suspend fun markOutcome(id: String, state: JournalState, error: String?): Int {
        trace += "${state.name}:${itemByEntry[id]}"
        rows.replaceAll { if (it.id == id) it.copy(state = state, error = error) else it }
        return 1
    }

    override suspend fun pending() = rows.filter { it.state == JournalState.PENDING }
    override suspend fun unresolved() =
        rows.filter { it.state == JournalState.PENDING || it.state == JournalState.FAILED }

    override suspend fun page(limit: Int, offset: Int) = rows.drop(offset).take(limit)
    override suspend fun byBatch(batchId: String) = rows.filter { it.batchId == batchId }
}

private class RecordingGateway(
    private val delegate: StorageGateway = FakeStorageGateway(),
    private val trace: MutableList<String>,
) : StorageGateway by delegate {
    var failNext: String? = null

    override suspend fun quarantine(items: List<MediaItem>, batch: BatchId): QuarantineResult {
        items.forEach { trace += "OP:${it.id.value}" }
        failNext?.let { throw IOException(it) }
        return delegate.quarantine(items, batch)
    }

    override suspend fun deletePermanently(items: List<MediaItem>): DeleteResult {
        items.forEach { trace += "OP:${it.id.value}" }
        failNext?.let { throw IOException(it) }
        return delegate.deletePermanently(items)
    }
}

private class FixedClock(private val now: Long) : Clock {
    override fun nowMillis(): Long = now
}

private fun destination(): Destination = Destination(
    source = SourceRef(
        id = SourceId("src-export"),
        treeUri = "fake://export",
        displayName = "Export",
        access = SourceRef.Access.RW,
        kind = SourceKind.RAW,
        itemCount = null,
        totalBytes = null,
        grantedAt = 0L,
    ),
)

private fun item(id: String): MediaItem = MediaItem(
    id = ItemId(id),
    uri = "fake://primary/$id",
    path = "fake://primary/$id",
    relativePath = id,
    mediaStoreId = null,
    mime = "image/gif",
    sizeBytes = 1024L,
    width = null,
    height = null,
    durationMs = null,
    dateAdded = 0L,
    dateModified = 0L,
    bucket = null,
    sourceId = SourceId("src-primary"),
    hash = null,
)
