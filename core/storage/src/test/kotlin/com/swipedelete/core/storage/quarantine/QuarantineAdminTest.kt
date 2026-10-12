package com.swipedelete.core.storage.quarantine

import com.swipedelete.core.common.Clock
import com.swipedelete.core.common.Sha256Hasher
import com.swipedelete.core.database.dao.JournalDao
import com.swipedelete.core.database.entity.JournalEntryEntity
import com.swipedelete.core.model.BatchId
import com.swipedelete.core.model.ItemId
import com.swipedelete.core.model.JournalState
import com.swipedelete.core.model.MediaItem
import com.swipedelete.core.model.OpKind
import com.swipedelete.core.model.SourceId
import com.swipedelete.core.model.SourceKind
import com.swipedelete.core.model.SourceRef
import com.swipedelete.core.storage.QuarantineResult
import com.swipedelete.core.storage.QuarantineStorageGateway
import com.swipedelete.core.storage.fake.FakeStorageGateway
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The only door to the permanent delete: journaled, gated, and never by accident. */
class QuarantineAdminTest {

    private val dirs = mutableListOf<File>()

    @AfterTest
    fun cleanUp() = dirs.forEach { it.deleteRecursively() }

    private inner class Harness {
        val base = tmp("base")
        val root = tmp("q")
        val fake = FakeStorageGateway().also {
            it.addSource(
                SourceRef(SourceId("s"), base.absolutePath, "P", SourceRef.Access.RW, SourceKind.RAW, null, null, 0L),
            )
        }
        val clock = object : Clock { override fun nowMillis() = 1_000L }
        val store = QuarantineStore(root, fake, Sha256Hasher(), clock)
        val gateway = QuarantineStorageGateway(fake, store)
        val trace = mutableListOf<String>()
        val journal = RecordingJournal(trace) { root.walkTopDown().count { it.isFile } }
        var gateOpen = true
        val admin = QuarantineAdmin(store, gateway, journal, clock, gate = { trace += "gate" })

        suspend fun hold(name: String, bytes: ByteArray) {
            val item = MediaItem(
                id = ItemId("id:$name"), uri = "content://t/$name", path = File(base, name).absolutePath,
                relativePath = name, mediaStoreId = null, mime = "image/gif", sizeBytes = bytes.size.toLong(),
                width = null, height = null, durationMs = null, dateAdded = 0L, dateModified = 1L, bucket = null,
                sourceId = SourceId("s"), hash = null,
            )
            fake.register(item, bytes)
            assertIs<QuarantineResult.Quarantined>(gateway.quarantine(listOf(item), BatchId("b-$name")))
        }

        fun filesHeld() = root.walkTopDown().count { it.isFile }
    }

    @Test
    fun `emptying without permission refuses and touches nothing`() = runTest {
        val h = Harness().apply { hold("a.gif", ByteArray(10)) }
        val before = h.filesHeld()

        val result = h.admin.emptyAll(allowPermanent = false)

        assertEquals(EmptyOutcome.Refused, result)
        assertEquals(before, h.filesHeld())
        assertTrue(h.journal.rows.none { it.opKind == OpKind.DELETE_PERMANENT })
    }

    @Test
    fun `emptying journals every file before deleting it, then records it done`() = runTest {
        val h = Harness().apply { hold("a.gif", ByteArray(10)); hold("b.gif", ByteArray(20)) }

        val result = h.admin.emptyAll(allowPermanent = true)

        assertEquals(EmptyOutcome.Emptied(count = 2, bytes = 30L), result)
        assertEquals(0, h.filesHeld())
        val purge = h.journal.rows.filter { it.opKind == OpKind.DELETE_PERMANENT }
        assertEquals(2, purge.size)
        assertTrue(purge.all { it.state == JournalState.DONE })
        assertTrue(h.journal.filesAtInsert.all { it == 4 }, "write-ahead: both data files and sidecars still existed at insert: ${h.journal.filesAtInsert}")
    }

    @Test
    fun `the recovery gate is awaited before any journal write`() = runTest {
        val h = Harness().apply { hold("a.gif", ByteArray(10)) }
        h.trace.clear()

        h.admin.emptyAll(allowPermanent = true)

        assertEquals("gate", h.trace.first())
    }

    @Test
    fun `contents lists what is held and restoreAll returns it to its place`() = runTest {
        val h = Harness().apply { hold("a.gif", ByteArray(10)); hold("b.gif", ByteArray(20)) }
        assertEquals(2, h.admin.contents().size)

        val restored = h.admin.restoreAll()

        assertEquals(2, restored.restored)
        assertEquals(0, restored.kept)
        assertEquals(0, h.admin.contents().size)
        assertTrue(File(h.base, "a.gif").isFile)
    }

    private fun tmp(p: String) = Files.createTempDirectory("sd-admin-$p-").toFile().also { dirs += it }
}

private class RecordingJournal(
    private val trace: MutableList<String>,
    private val filesNow: () -> Int,
) : JournalDao {
    val rows = mutableListOf<JournalEntryEntity>()
    val filesAtInsert = mutableListOf<Int>()

    override suspend fun insertPending(entry: JournalEntryEntity): Long {
        if (entry.opKind == OpKind.DELETE_PERMANENT) filesAtInsert += filesNow()
        trace += "PENDING"
        rows += entry
        return rows.size.toLong()
    }

    override suspend fun markOutcome(id: String, state: JournalState, error: String?): Int {
        rows.replaceAll { if (it.id == id) it.copy(state = state, error = error) else it }
        return 1
    }

    override suspend fun pending() = rows.filter { it.state == JournalState.PENDING }
    override suspend fun unresolved() = rows.filter { it.state == JournalState.PENDING || it.state == JournalState.FAILED }
    override suspend fun page(limit: Int, offset: Int) = rows.drop(offset).take(limit)
    override suspend fun byBatch(batchId: String) = rows.filter { it.batchId == batchId }
}
