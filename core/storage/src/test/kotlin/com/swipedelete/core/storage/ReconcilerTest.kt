package com.swipedelete.core.storage

import com.swipedelete.core.common.Clock
import com.swipedelete.core.common.Sha256Hasher
import com.swipedelete.core.database.dao.JournalDao
import com.swipedelete.core.database.entity.JournalEntryEntity
import com.swipedelete.core.model.BatchId
import com.swipedelete.core.model.ItemId
import com.swipedelete.core.model.JournalEntry
import com.swipedelete.core.model.JournalOutcome
import com.swipedelete.core.model.JournalState
import com.swipedelete.core.model.MediaItem
import com.swipedelete.core.model.OpKind
import com.swipedelete.core.model.Resolved
import com.swipedelete.core.model.SourceId
import com.swipedelete.core.model.SourceKind
import com.swipedelete.core.model.SourceRef
import com.swipedelete.core.storage.fake.FakeStorageGateway
import com.swipedelete.core.storage.quarantine.QuarantineLayout
import com.swipedelete.core.storage.quarantine.QuarantineStore
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * AC-5: process death at any point leaves no item in an unknown state;
 * reconciliation resolves every unresolved journal row.
 *
 * Reconciliation is a **classification**, not a repair: it reads the journal,
 * the quarantine sidecars and one "does this exist?" probe, then writes only the
 * journal rows' state. It must never delete, move or rewrite a user file
 * (spec §6.2: the journal is immutable history).
 *
 * `FAILED` rows are reconciled as well as `PENDING` ones: a mid-batch
 * delegate failure marks *every* row of the call `FAILED`, including items the
 * delegate physically moved before failing.
 *
 * The fixtures are real: a [FakeStorageGateway] holding real bytes, a
 * [QuarantineStore] over a real temporary directory, and a [RecordingJournalDao]
 * seeded with journal rows. `exists` is the app-supplied probe the reconciler
 * calls instead of the gateway (which exposes no existence operation).
 */
class ReconcilerTest {

    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun cleanUp() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun `a pending entry whose file is still at source becomes ABORTED`() = runTest {
        val h = newHarness()
        val item = h.item("Memes/a.gif", "still here".toByteArray())
        h.journal.seed(h.row(id = "j1", batchId = "b1", fromUri = item.path!!))

        val report = h.reconciler.reconcile()

        assertEquals(listOf(Resolved("j1", JournalOutcome.ABORTED)), report.resolved)
        assertTrue(report.unknown.isEmpty(), "a row found at its source is not unknown")
        assertEquals(JournalState.ABORTED, h.journal.stateOf("j1"))
        assertTrue(h.fake.fileExists(item.path!!), "the original must still be at its source")
    }

    @Test
    fun `a pending entry whose file is at the destination becomes DONE`() = runTest {
        val h = newHarness()
        val item = h.item("Memes/a.gif", "moved away".toByteArray())
        h.quarantineForReal(item, batch = "b1")
        assertFalse(h.fake.fileExists(item.path!!), "the quarantine copy must have replaced the original")
        h.journal.seed(h.row(id = "j1", batchId = "b1", fromUri = item.path!!))

        val report = h.reconciler.reconcile()

        assertEquals(listOf(Resolved("j1", JournalOutcome.DONE)), report.resolved)
        assertTrue(report.unknown.isEmpty())
        assertEquals(JournalState.DONE, h.journal.stateOf("j1"))
        assertEquals("reconciled_done", h.journal.errorOf("j1"), "the recorded reason must be stable")
    }

    @Test
    fun `a pending entry found at neither location surfaces in the Recovery Report`() = runTest {
        val h = newHarness()
        // A batch with no quarantine sidecar, naming a URI that exists nowhere.
        h.journal.seed(h.row(id = "j1", batchId = "b9", fromUri = File(h.base, "gone.gif").absolutePath))

        val report = h.reconciler.reconcile()

        assertEquals(1, report.unknown.size)
        assertEquals("j1", report.unknown.single().id)
        assertTrue(report.resolved.isEmpty(), "an unknown row is not resolved")
        assertEquals(JournalState.PENDING, h.journal.stateOf("j1"))
        assertEquals(0, h.journal.markOutcomeCalls, "an unknown row must not be written at all")

        // A second pass must leave it exactly where it is: unknown is reported,
        // never guessed at and never rewritten.
        val second = h.reconciler.reconcile()

        assertEquals(listOf("j1"), second.unknown.map { it.id })
        assertEquals(JournalState.PENDING, h.journal.stateOf("j1"))
        assertEquals(0, h.journal.markOutcomeCalls)
    }

    @Test
    fun `reconcile is idempotent`() = runTest {
        val h = newHarness()
        val item = h.item("Memes/a.gif", "still here".toByteArray())
        h.journal.seed(h.row(id = "j1", batchId = "b1", fromUri = item.path!!))

        val first = h.reconciler.reconcile()
        val statesAfterFirst = h.journal.states()

        val second = h.reconciler.reconcile()

        assertEquals(1, first.resolved.size)
        assertTrue(second.unknown.isEmpty(), "a fully resolved journal has nothing left to report")
        assertTrue(second.resolved.isEmpty(), "a terminal row must not be re-classified")
        assertEquals(statesAfterFirst, h.journal.states(), "no row's state may change on a second pass")
    }

    @Test
    fun `a FAILED row whose file is in quarantine is corrected to DONE`() = runTest {
        val h = newHarness()
        val item = h.item("Memes/a.gif", "moved before the failure".toByteArray())
        h.quarantineForReal(item, batch = "b1")
        // Task 15: a mid-batch delegate failure marks every row FAILED, even the
        // ones the delegate physically moved before failing.
        h.journal.seed(
            h.row(
                id = "j1",
                batchId = "b1",
                fromUri = item.path!!,
                state = JournalState.FAILED,
                error = "injected mid-batch failure",
            ),
        )

        val report = h.reconciler.reconcile()

        assertEquals(listOf(Resolved("j1", JournalOutcome.DONE)), report.resolved)
        assertEquals(JournalState.DONE, h.journal.stateOf("j1"))
        assertEquals(
            "reconciled_done (injected mid-batch failure)",
            h.journal.errorOf("j1"),
            "a corrected row must keep the failure that made it FAILED",
        )
    }

    @Test
    fun `a FAILED row whose file is still at source is reported as ABORTED`() = runTest {
        val h = newHarness()
        val item = h.item("Memes/a.gif", "untouched".toByteArray())
        h.journal.seed(
            h.row(
                id = "j1",
                batchId = "b1",
                fromUri = item.path!!,
                state = JournalState.FAILED,
                error = "injected mid-batch failure",
            ),
        )

        val report = h.reconciler.reconcile()

        assertEquals(listOf(Resolved("j1", JournalOutcome.ABORTED)), report.resolved)
        assertEquals(JournalState.ABORTED, h.journal.stateOf("j1"))
        assertTrue(h.fake.fileExists(item.path!!), "the original must be untouched")
        assertContentEquals("untouched".toByteArray(), h.contentOf(item))
        assertEquals(
            "reconciled_aborted (injected mid-batch failure)",
            h.journal.errorOf("j1"),
            "the prior failure reason must be preserved in the recorded reason",
        )
    }

    @Test
    fun `reconciliation never deletes or moves user files`() = runTest {
        val h = newHarness()
        val atSource = h.item("Memes/a.gif", "kept".toByteArray())
        val quarantined = h.item("Memes/b.gif", "quarantined".toByteArray())
        h.quarantineForReal(quarantined, batch = "b2")
        // One row per classification path: ABORTED, DONE and unknown.
        h.journal.seed(h.row(id = "j1", batchId = "b1", fromUri = atSource.path!!))
        h.journal.seed(h.row(id = "j2", batchId = "b2", fromUri = quarantined.path!!))
        h.journal.seed(
            h.row(id = "j3", batchId = "b9", fromUri = File(h.base, "ghost.gif").absolutePath),
        )

        val gatewayBefore = h.gatewaySnapshot(atSource, quarantined)
        val quarantineBefore = h.quarantineSnapshot()

        val report = h.reconciler.reconcile()

        assertEquals(3, report.resolved.size + report.unknown.size)
        assertEquals(gatewayBefore, h.gatewaySnapshot(atSource, quarantined), "no gateway item may change")
        assertEquals(quarantineBefore, h.quarantineSnapshot(), "no quarantined file may change")
        assertEquals(1, h.quarantineSnapshot().size, "the quarantined count must be unchanged")
        assertTrue(h.fake.fileExists(atSource.path!!), "the surviving original must stay at its source")
        assertFalse(h.fake.fileExists(quarantined.path!!), "a quarantined original must not reappear")
    }

    @Test
    fun `an unparseable or missing sidecar does not crash reconciliation`() = runTest {
        val h = newHarness()
        val item = h.item("Memes/a.gif", "still at source".toByteArray())
        // A garbage sidecar naming this item's quarantine location, and an
        // orphan sidecar whose data file is already gone.
        val corrupt = File(h.root, "b1/Memes/a.gif${QuarantineLayout.SIDECAR_SUFFIX}")
        corrupt.parentFile!!.mkdirs()
        corrupt.writeText("{ this is not json")
        val orphan = File(h.root, "b1/Memes/gone.gif${QuarantineLayout.SIDECAR_SUFFIX}")
        orphan.writeText("{}")
        h.journal.seed(h.row(id = "j1", batchId = "b1", fromUri = item.path!!))
        h.journal.seed(
            h.row(id = "j2", batchId = "b1", fromUri = File(h.base, "nowhere.gif").absolutePath),
        )

        val report = h.reconciler.reconcile()

        // The unreadable sidecar is never treated as evidence of a completed
        // quarantine: the row is classified from the real source probe.
        assertEquals(listOf(Resolved("j1", JournalOutcome.ABORTED)), report.resolved)
        assertEquals(listOf("j2"), report.unknown.map { it.id })
        assertEquals(JournalState.ABORTED, h.journal.stateOf("j1"))
        assertEquals(JournalState.PENDING, h.journal.stateOf("j2"))
        assertEquals(1, h.journal.markOutcomeCalls, "only the resolvable row may be written")
        assertTrue(corrupt.isFile, "reconciliation must not clean up quarantine files")
        assertTrue(orphan.isFile, "reconciliation must not clean up quarantine files")
    }

    @Test
    fun `a row whose verdict cannot be written is surfaced rather than claimed resolved`() = runTest {
        val h = newHarness()
        val item = h.item("Memes/a.gif", "still here".toByteArray())
        h.journal.seed(h.row(id = "j1", batchId = "b1", fromUri = item.path!!))
        h.journal.markOutcomeSucceeds = false

        assertFailsWith<IllegalStateException> { h.reconciler.reconcile() }
    }

    @Test
    fun `a probe that throws for one row does not abort the whole pass`() = runTest {
        val h = newHarness()
        val quarantined = h.item("Memes/a.gif", "quarantined".toByteArray())
        h.quarantineForReal(quarantined, batch = "b1")
        val atSource = h.item("Memes/b.gif", "kept".toByteArray())
        val poison = File(h.base, "poison.gif").absolutePath
        h.journal.seed(h.row(id = "j1", batchId = "b1", fromUri = quarantined.path!!))
        h.journal.seed(h.row(id = "j2", batchId = "b1", fromUri = poison))
        h.journal.seed(h.row(id = "j3", batchId = "b1", fromUri = atSource.path!!))
        // A probe that dies for exactly one row, as a SecurityException on a
        // content URI would.
        val reconciler = Reconciler(h.journal, h.store) { uri ->
            if (uri == poison) throw SecurityException("denied: $uri")
            h.fake.fileExists(uri)
        }

        val report = reconciler.reconcile()

        assertEquals(
            listOf(Resolved("j1", JournalOutcome.DONE), Resolved("j3", JournalOutcome.ABORTED)),
            report.resolved,
            "the rows around the failing probe must still be classified",
        )
        assertEquals(listOf("j2"), report.unknown.map { it.id }, "the un-probeable row is reported")
        assertEquals(JournalState.DONE, h.journal.stateOf("j1"))
        assertEquals(JournalState.PENDING, h.journal.stateOf("j2"))
        assertEquals(JournalState.ABORTED, h.journal.stateOf("j3"))
    }

    @Test
    fun `a move entry completed before the interruption is DONE`() = runTest {
        val h = newHarness()
        val moved = h.item("Export/a.gif", "moved".toByteArray())
        h.journal.seed(
            h.row(
                id = "j1",
                batchId = "b1",
                fromUri = File(h.base, "Memes/a.gif").absolutePath,
                toUri = moved.path!!,
                opKind = OpKind.MOVE,
            ),
        )

        val report = h.reconciler.reconcile()

        assertEquals(listOf(Resolved("j1", JournalOutcome.DONE)), report.resolved)
        assertEquals(JournalState.DONE, h.journal.stateOf("j1"))
    }

    @Test
    fun `a move entry that never left its source is ABORTED`() = runTest {
        val h = newHarness()
        val stuck = h.item("Memes/a.gif", "stuck".toByteArray())
        h.journal.seed(
            h.row(
                id = "j1",
                batchId = "b1",
                fromUri = stuck.path!!,
                toUri = File(h.base, "Export/a.gif").absolutePath,
                opKind = OpKind.MOVE,
            ),
        )

        val report = h.reconciler.reconcile()

        assertEquals(listOf(Resolved("j1", JournalOutcome.ABORTED)), report.resolved)
        assertEquals(JournalState.ABORTED, h.journal.stateOf("j1"))
        assertTrue(h.fake.fileExists(stuck.path!!))
    }

    @Test
    fun `a move entry found at neither location is unknown`() = runTest {
        val h = newHarness()
        h.journal.seed(
            h.row(
                id = "j1",
                batchId = "b1",
                fromUri = File(h.base, "Memes/gone.gif").absolutePath,
                toUri = File(h.base, "Export/gone.gif").absolutePath,
                opKind = OpKind.MOVE,
            ),
        )

        val report = h.reconciler.reconcile()

        assertEquals(listOf("j1"), report.unknown.map { it.id })
        assertEquals(JournalState.PENDING, h.journal.stateOf("j1"))
    }

    @Test
    fun `a restore that completed before the interruption is DONE`() = runTest {
        val h = newHarness()
        val item = h.item("Memes/a.gif", "restored for real".toByteArray())
        val quarantined = h.quarantineForReal(item, batch = "b1")
        // Really complete the restore: the bytes are back at the original path
        // and the quarantine copy (and its sidecar) are gone.
        assertTrue(h.store.restore(quarantined) is RestoreResult.Restored)
        assertTrue(File(item.path!!).isFile, "the restored bytes must be back on disk")
        assertTrue(h.store.scan().isEmpty(), "the quarantine copy must be gone")
        h.journal.seed(h.restoreRow(id = "j1", quarantined = quarantined, originalUri = item.path!!))

        val report = h.reconciler.reconcile()

        assertEquals(listOf(Resolved("j1", JournalOutcome.DONE)), report.resolved)
        assertEquals(JournalState.DONE, h.journal.stateOf("j1"))
        assertEquals("reconciled_done", h.journal.errorOf("j1"))
        assertContentEquals("restored for real".toByteArray(), File(item.path!!).readBytes())
    }

    @Test
    fun `a restore interrupted before the bytes were written back is ABORTED`() = runTest {
        val h = newHarness()
        val item = h.item("Memes/a.gif", "still quarantined".toByteArray())
        val quarantined = h.quarantineForReal(item, batch = "b1")
        assertFalse(File(item.path!!).exists(), "nothing may be back at the original path")
        h.journal.seed(h.restoreRow(id = "j1", quarantined = quarantined, originalUri = item.path!!))

        val report = h.reconciler.reconcile()

        assertEquals(listOf(Resolved("j1", JournalOutcome.ABORTED)), report.resolved)
        assertEquals(JournalState.ABORTED, h.journal.stateOf("j1"))
        assertEquals("reconciled_aborted", h.journal.errorOf("j1"))
        assertTrue(File(quarantined.toUri!!).isFile, "the quarantined copy must survive")
    }

    @Test
    fun `a delete-permanent entry is DONE when the file is gone and ABORTED when it is not`() = runTest {
        val h = newHarness()
        val survivor = h.item("Memes/a.gif", "survivor".toByteArray())
        h.journal.seed(
            h.row(
                id = "j1",
                batchId = "b1",
                fromUri = File(h.base, "Memes/gone.gif").absolutePath,
                opKind = OpKind.DELETE_PERMANENT,
            ),
        )
        h.journal.seed(
            h.row(
                id = "j2",
                batchId = "b1",
                fromUri = survivor.path!!,
                opKind = OpKind.DELETE_PERMANENT,
            ),
        )

        val report = h.reconciler.reconcile()

        assertEquals(
            listOf(
                Resolved("j1", JournalOutcome.DONE),
                Resolved("j2", JournalOutcome.ABORTED),
            ),
            report.resolved,
        )
        assertEquals(JournalState.DONE, h.journal.stateOf("j1"))
        assertEquals(JournalState.ABORTED, h.journal.stateOf("j2"))
        assertTrue(h.fake.fileExists(survivor.path!!))
    }

    @Test
    fun `an op kind with no reconciliation rule is unknown`() = runTest {
        val h = newHarness()
        val item = h.item("Memes/a.gif", "compressed".toByteArray())
        h.journal.seed(
            h.row(id = "j1", batchId = "b1", fromUri = item.path!!, opKind = OpKind.COMPRESS),
        )

        val report = h.reconciler.reconcile()

        assertEquals(listOf("j1"), report.unknown.map { it.id })
        assertEquals(JournalState.PENDING, h.journal.stateOf("j1"))
    }

    // --- Harness ---

    private inner class Harness {
        val base: File = tempDir("base")
        val root: File = tempDir("quarantine")
        val fake = FakeStorageGateway()
        val source = SourceRef(
            id = SourceId("src-primary"),
            treeUri = base.absolutePath,
            displayName = "Primary",
            access = SourceRef.Access.RW,
            kind = SourceKind.RAW,
            itemCount = null,
            totalBytes = null,
            grantedAt = 0L,
        )
        val store = QuarantineStore(root, fake, Sha256Hasher(), ReconcilerClock(NOW))
        val journal = RecordingJournalDao()

        /**
         * The app-layer existence probe. In tests it sees both the fake
         * gateway's items and the real bytes a [QuarantineStore.restore] puts
         * back on disk, mirroring a real probe that checks the filesystem.
         */
        val reconciler = Reconciler(journal, store) { fake.fileExists(it) || File(it).isFile }

        init {
            fake.addSource(source)
        }

        fun item(relative: String, content: ByteArray): MediaItem {
            val path = File(base, relative).absolutePath
            val item = MediaItem(
                id = ItemId("id:$relative"),
                // uri == path keeps the journal's fromUri, the sidecar's
                // originalUri and the fake gateway's key the same string.
                uri = path,
                path = path,
                relativePath = relative,
                mediaStoreId = null,
                mime = FakeStorageGateway.mimeFor(relative),
                sizeBytes = content.size.toLong(),
                width = null,
                height = null,
                durationMs = null,
                dateAdded = 0L,
                dateModified = 0L,
                bucket = null,
                sourceId = source.id,
                hash = null,
            )
            fake.register(item, content)
            return item
        }

        suspend fun quarantineForReal(item: MediaItem, batch: String): JournalEntry =
            store.store(item, BatchId(batch))

        /**
         * The row shape [JournalingStorageGateway.restore] actually writes
         * (JournalingStorageGateway.kt:109-133): a **fresh random batch id**
         * from `newBatchId()`, `fromUri` = the quarantined file's path and
         * `toUri` = the original path. A random batch id can never equal a
         * sidecar's batch, so only the quarantine *path* can correlate the row.
         */
        fun restoreRow(id: String, quarantined: JournalEntry, originalUri: String): JournalEntryEntity =
            row(
                id = id,
                batchId = UUID.randomUUID().toString(),
                fromUri = quarantined.toUri!!,
                toUri = originalUri,
                opKind = OpKind.RESTORE,
                itemId = quarantined.itemId.value,
            )

        fun contentOf(item: MediaItem): ByteArray = fake.openStream(item).use { it.readBytes() }

        /** Every observable gateway item (path + bytes), for before/after comparison. */
        suspend fun gatewaySnapshot(vararg items: MediaItem): Map<String, String> =
            items.associate { item ->
                val path = item.path!!
                path to if (fake.fileExists(path)) bytesToText(contentOf(item)) else ABSENT
            }

        /** Every quarantined file (entry id + path + bytes), for before/after comparison. */
        fun quarantineSnapshot(): Map<String, String> =
            store.scan().associate { entry ->
                val bytes = File(entry.path).let { if (it.isFile) bytesToText(it.readBytes()) else ABSENT }
                entry.entry.id to "${entry.path}|$bytes"
            }

        fun row(
            id: String,
            batchId: String,
            fromUri: String,
            toUri: String? = null,
            opKind: OpKind = OpKind.QUARANTINE,
            state: JournalState = JournalState.PENDING,
            itemId: String = "item:$id",
            error: String? = null,
        ): JournalEntryEntity = JournalEntryEntity(
            id = id,
            batchId = batchId,
            sessionId = null,
            itemId = itemId,
            opKind = opKind,
            fromUri = fromUri,
            toUri = toUri,
            hash = null,
            sizeBytes = 0L,
            state = state,
            createdAt = NOW,
            expiresAt = NOW + RETENTION_MILLIS,
            error = error,
        )
    }

    private fun newHarness(): Harness = Harness()

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory("sd-reconciler-$prefix-").toFile().also { tempDirs += it }
}

/**
 * In-memory [JournalDao] seeded with rows. Only the state may change during
 * reconciliation, so it records every write and exposes the exact row states.
 */
private class RecordingJournalDao : JournalDao {
    private val rows = mutableListOf<JournalEntryEntity>()

    /** Every write reconciliation performed; unknown rows must leave this at 0. */
    var markOutcomeCalls: Int = 0
        private set

    /** When false, [markOutcome] behaves as if the row did not exist. */
    var markOutcomeSucceeds: Boolean = true

    fun seed(row: JournalEntryEntity) {
        rows += row
    }

    fun stateOf(id: String): JournalState = rows.single { it.id == id }.state
    fun errorOf(id: String): String? = rows.single { it.id == id }.error
    fun states(): Map<String, JournalState> = rows.associate { it.id to it.state }

    override suspend fun insertPending(entry: JournalEntryEntity): Long {
        rows += entry
        return rows.size.toLong()
    }

    override suspend fun markOutcome(id: String, state: JournalState, error: String?): Int {
        markOutcomeCalls++
        val index = rows.indexOfFirst { it.id == id }
        if (index < 0 || !markOutcomeSucceeds) return 0
        rows[index] = rows[index].copy(state = state, error = error)
        return 1
    }

    override suspend fun pending(): List<JournalEntryEntity> =
        rows.filter { it.state == JournalState.PENDING }

    override suspend fun unresolved(): List<JournalEntryEntity> =
        rows.filter { it.state == JournalState.PENDING || it.state == JournalState.FAILED }

    override suspend fun page(limit: Int, offset: Int): List<JournalEntryEntity> =
        rows.drop(offset).take(limit)

    override suspend fun byBatch(batchId: String): List<JournalEntryEntity> =
        rows.filter { it.batchId == batchId }
}

/** A clock pinned to [now]; named uniquely because top-level classes share a package. */
private class ReconcilerClock(private val now: Long) : Clock {
    override fun nowMillis(): Long = now
}

private const val ABSENT = "ABSENT"
private const val NOW = 1_700_000_000_000L
private const val RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000

private fun bytesToText(bytes: ByteArray): String =
    bytes.joinToString(",") { (it.toInt() and 0xFF).toString() }
