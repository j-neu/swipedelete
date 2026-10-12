package com.swipedelete.core.storage.quarantine

import com.swipedelete.core.common.Clock
import com.swipedelete.core.common.Sha256Hasher
import com.swipedelete.core.database.dao.JournalDao
import com.swipedelete.core.database.entity.JournalEntryEntity
import com.swipedelete.core.model.BatchId
import com.swipedelete.core.model.ItemId
import com.swipedelete.core.model.JournalState
import com.swipedelete.core.model.MediaItem
import com.swipedelete.core.model.SourceId
import com.swipedelete.core.model.SourceKind
import com.swipedelete.core.model.SourceRef
import com.swipedelete.core.storage.JournalingStorageGateway
import com.swipedelete.core.storage.QuarantineResult
import com.swipedelete.core.storage.QuarantineStorageGateway
import com.swipedelete.core.storage.RestoreResult
import com.swipedelete.core.storage.fake.FakeStorageGateway
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AC-17 / M2-T06: the journal's story of each quarantined file, and restoring it safely. */
class QuarantineHistoryTest {

    private val dirs = mutableListOf<File>()

    @AfterTest
    fun cleanUp() = dirs.forEach { it.deleteRecursively() }

    private inner class Harness {
        val base = tmp("base")
        val root = tmp("q")
        val fake = FakeStorageGateway().also {
            it.addSource(SourceRef(SourceId("s"), base.absolutePath, "P", SourceRef.Access.RW, SourceKind.RAW, null, null, 0L))
        }
        val clock = object : Clock { override fun nowMillis() = 1_000L }
        val store = QuarantineStore(root, fake, Sha256Hasher(), clock)
        val journal = MemoryJournal()
        val gateway = JournalingStorageGateway(QuarantineStorageGateway(fake, store), journal, clock)
        val admin = QuarantineAdmin(store, gateway, journal, clock)

        suspend fun hold(name: String, bytes: ByteArray): MediaItem {
            val item = MediaItem(
                id = ItemId("id:$name"), uri = "content://t/$name", path = File(base, name).absolutePath,
                relativePath = name, mediaStoreId = null, mime = "image/gif", sizeBytes = bytes.size.toLong(),
                width = null, height = null, durationMs = null, dateAdded = 0L, dateModified = 1L, bucket = null,
                sourceId = SourceId("s"), hash = null,
            )
            fake.register(item, bytes)
            assertIs<QuarantineResult.Quarantined>(gateway.quarantine(listOf(item), BatchId("b-$name")))
            return item
        }
    }

    @Test
    fun `a quarantined file is restorable and carries its quarantine copy`() = runTest {
        val h = Harness().apply { hold("a.gif", ByteArray(10)) }

        val item = h.admin.history().single()

        assertEquals(HistoryStatus.Restorable, item.status)
        assertNotNull(item.held)
    }

    @Test
    fun `restoring from history returns the exact bytes and the item reads as restored`() = runTest {
        val bytes = "original-bytes".toByteArray()
        val h = Harness()
        h.hold("a.gif", bytes)

        val result = h.admin.restore(h.admin.history().single())

        assertIs<RestoreResult.Restored>(result)
        assertContentEquals(bytes, File(h.base, "a.gif").readBytes())
        val statuses = h.admin.history().filter { it.row.opKind.name == "QUARANTINE" }.map { it.status }
        assertEquals(listOf(HistoryStatus.Restored), statuses)
    }

    @Test
    fun `after Empty the item is listed as no longer restorable`() = runTest {
        val h = Harness().apply { hold("a.gif", ByteArray(10)) }
        h.admin.emptyAll(allowPermanent = true)

        val quarantineRow = h.admin.history().single { it.row.opKind.name == "QUARANTINE" }

        assertEquals(HistoryStatus.NoLongerRestorable, quarantineRow.status)
        assertNull(quarantineRow.held)
    }

    @Test
    fun `restoring onto an occupied name conflicts and never overwrites`() = runTest {
        val h = Harness()
        h.hold("a.gif", "mine".toByteArray())
        File(h.base, "a.gif").writeBytes("newcomer".toByteArray())

        val result = h.admin.restore(h.admin.history().single())

        assertIs<RestoreResult.Conflict>(result)
        assertEquals("newcomer", File(h.base, "a.gif").readText())
        assertEquals(HistoryStatus.Restorable, h.admin.history().single { it.row.opKind.name == "QUARANTINE" }.status, "still held for a later restore")
    }

    @Test
    fun `keep both restores beside the occupant under a new name`() = runTest {
        val h = Harness()
        h.hold("a.gif", "mine".toByteArray())
        File(h.base, "a.gif").writeBytes("newcomer".toByteArray())

        val result = h.admin.restore(h.admin.history().single(), keepBoth = true)

        val restored = assertIs<RestoreResult.Restored>(result)
        assertEquals("newcomer", File(h.base, "a.gif").readText())
        assertEquals("a (restored).gif", File(restored.to).name)
        assertEquals("mine", File(restored.to).readText())
        assertTrue(h.admin.contents().isEmpty())
    }

    private fun tmp(p: String) = Files.createTempDirectory("sd-hist-$p-").toFile().also { dirs += it }
}

private class MemoryJournal : JournalDao {
    val rows = mutableListOf<JournalEntryEntity>()
    override suspend fun insertPending(entry: JournalEntryEntity): Long { rows += entry; return rows.size.toLong() }
    override suspend fun markOutcome(id: String, state: JournalState, error: String?): Int {
        rows.replaceAll { if (it.id == id) it.copy(state = state, error = error) else it }
        return 1
    }
    override suspend fun pending() = rows.filter { it.state == JournalState.PENDING }
    override suspend fun unresolved() = rows.filter { it.state == JournalState.PENDING || it.state == JournalState.FAILED }
    override suspend fun page(limit: Int, offset: Int) = rows.sortedByDescending { it.createdAt }.drop(offset).take(limit)
    override suspend fun byBatch(batchId: String) = rows.filter { it.batchId == batchId }
}
