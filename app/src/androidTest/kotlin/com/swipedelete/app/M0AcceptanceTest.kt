package com.swipedelete.app

import android.content.Context
import android.os.Environment
import android.os.ParcelFileDescriptor
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.swipedelete.app.di.StorageGraph
import com.swipedelete.app.di.assembleStorageGraph
import com.swipedelete.core.common.Clock
import com.swipedelete.core.common.Sha256Hasher
import com.swipedelete.core.database.SwipeDeleteDatabase
import com.swipedelete.core.database.dao.JournalDao
import com.swipedelete.core.model.BatchId
import com.swipedelete.core.model.ItemId
import com.swipedelete.core.model.JournalState
import com.swipedelete.core.model.MediaItem
import com.swipedelete.core.model.OpKind
import com.swipedelete.core.model.SourceId
import com.swipedelete.core.storage.DeleteResult
import com.swipedelete.core.storage.ProtectedRootsStorageGateway
import com.swipedelete.core.storage.QuarantineResult
import com.swipedelete.core.storage.RestoreResult
import com.swipedelete.core.storage.StorageGateway
import com.swipedelete.core.storage.raw.RawStorageGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.InputStream

/**
 * M0's exit gate (M0-T17), run on a real device against **real files, real Room
 * and the real assembled graph**:
 *
 * - `aFileQuarantinedThenRestoredComesBackByteIdentical` — **AC-3**.
 * - `killingTheProcessMidQuarantineLeavesNoPendingRecordUnresolved` — **AC-5**.
 * - `everyMutationProducedExactlyOneJournalRowWithATerminalState` — **AC-1**.
 * - `aProtectedRootItemProducesZeroJournalRowsAndZeroMoves` — **AC-7**.
 *
 * Nothing here is a fake. The graph comes from the same
 * [assembleStorageGraph] the Hilt module builds (Task 24), the journal is a real
 * on-disk Room database, the quarantine is the real [QuarantineStore] over a real
 * app-external directory, and the gateway is the real [RawStorageGateway] over a
 * real filesystem root — so a "restore is byte-identical" claim is a claim about
 * bytes that were actually moved through the production stack.
 *
 * **Method names are camelCase, not backticked prose.** `minSdk` is 26, so D8
 * targets DEX version 038, where spaces in method names are illegal; backticked
 * names make the APK undeployable (the same defect the `:feature:settings`
 * device pass exposed). The prose names live in this doc and in the report.
 */
@RunWith(AndroidJUnit4::class)
class M0AcceptanceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val hasher = Sha256Hasher()
    private val clock = object : Clock {
        override fun nowMillis(): Long = System.currentTimeMillis()
    }

    private lateinit var db: SwipeDeleteDatabase
    private lateinit var journal: JournalDao
    private lateinit var runRoot: File
    private lateinit var sourceRoot: File
    private lateinit var quarantineRoot: File

    @Before
    fun setUp() {
        // AC-3/5/7 on "real files" means a real filesystem root, not the in-memory
        // fake. The raw gateway is the only concrete real-file gateway, and it
        // needs All Files Access to be *constructed*, so grant it through appops
        // (the same thing `adb shell appops set <pkg> MANAGE_EXTERNAL_STORAGE
        // allow` does) and record whether it actually stuck. If it did not, the
        // suite still runs over real files, but in the app-specific external dir
        // — the report states which one was used.
        val allFilesAccess = grantAllFilesAccess()
        val base = if (allFilesAccess) {
            File(Environment.getExternalStorageDirectory(), SHARED_DIR)
        } else {
            File(context.getExternalFilesDir(null) ?: context.filesDir, APP_EXTERNAL_DIR)
        }
        runRoot = File(base, "run-${System.nanoTime()}")
        sourceRoot = File(runRoot, "source")
        quarantineRoot = File(runRoot, "quarantine")
        assertTrue("real source root must be created", sourceRoot.mkdirs() || sourceRoot.isDirectory)
        assertTrue(
            "real quarantine root must be created",
            quarantineRoot.mkdirs() || quarantineRoot.isDirectory,
        )

        context.deleteDatabase(DB_NAME)
        db = Room.databaseBuilder(context, SwipeDeleteDatabase::class.java, DB_NAME).build()
        journal = db.journalDao()

        android.util.Log.i(
            TAG,
            "M0 acceptance root=${sourceRoot.absolutePath} " +
                "sharedStorage=${sourceRoot.absolutePath.startsWith("/storage/emulated")} " +
                "allFilesAccess=$allFilesAccess",
        )
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
        context.deleteDatabase(DB_NAME)
        if (::runRoot.isInitialized && runRoot.isDirectory) runRoot.deleteRecursively()
    }

    // --- AC-3 ------------------------------------------------------------------

    @Test
    fun aFileQuarantinedThenRestoredComesBackByteIdentical() = runBlocking<Unit> {
        val graph = newGraph()
        val sourceId = sourceId()
        val original = writeFile("notes.txt", payload(seed = 1))
        assertTrue("precondition: the source is a real file", original.isFile)
        val digestBefore = hasher.sha256(original.inputStream())

        val quarantined = graph.gateway.quarantine(
            listOf(item(original, sourceId)),
            BatchId("ac3-quarantine"),
        )
        val entry = (quarantined as QuarantineResult.Quarantined).entries.single()

        assertFalse("the original must be gone after a verified quarantine", original.exists())
        assertEquals(
            "the quarantined copy must hash to the original digest",
            digestBefore,
            entry.hash,
        )
        val scanned = graph.quarantineStore.scan()
        assertEquals("exactly one sidecar must be present", 1, scanned.size)
        assertEquals(
            "the sidecar must point at the journal's quarantine path",
            entry.toUri,
            scanned.single().path,
        )
        assertTrue(
            "the quarantined bytes must still be on disk",
            File(scanned.single().path).isFile,
        )

        val restored = graph.gateway.restore(entry)
        assertEquals(
            "restore must place the bytes back at the original path",
            RestoreResult.Restored(original.absolutePath),
            restored,
        )
        assertTrue("the restored file must exist", original.isFile)
        assertEquals(
            "the restored bytes must be byte-identical (hash, not length)",
            digestBefore,
            hasher.sha256(original.inputStream()),
        )

        // A restore into a path something else now occupies must refuse and touch
        // nothing — the real (absolute-path) occupancy conflict, not the SAF
        // content-URI fallback that passes for the wrong reason.
        val occupied = writeFile("occupied.txt", payload(seed = 2))
        val second = graph.gateway.quarantine(
            listOf(item(occupied, sourceId)),
            BatchId("ac3-conflict"),
        )
        val secondEntry = (second as QuarantineResult.Quarantined).entries.single()
        val occupying = writeFile("occupied.txt", payload(seed = 3))
        val occupyingDigest = hasher.sha256(occupying.inputStream())

        val conflict = graph.gateway.restore(secondEntry)
        assertTrue(
            "restore into an occupied path must report Conflict, was $conflict",
            conflict is RestoreResult.Conflict,
        )
        assertEquals(
            "the occupying file must be untouched",
            occupyingDigest,
            hasher.sha256(occupied.inputStream()),
        )
        assertTrue(
            "the quarantined copy must remain when restore conflicts",
            File(secondEntry.toUri!!).isFile,
        )
        assertTrue(
            "the sidecar must remain when restore conflicts",
            graph.quarantineStore.scan().any { it.path == secondEntry.toUri },
        )
    }

    // --- AC-5 ------------------------------------------------------------------

    @Test
    fun killingTheProcessMidQuarantineLeavesNoPendingRecordUnresolved() = runBlocking<Unit> {
        // The two real stop points a `kill -9` could land on. The kill is modelled
        // exactly as the host chaos sweep does it — the coroutine is abandoned by
        // throwing a CancellationException at a *real* filesystem boundary, not by
        // an OS signal (a run cannot `am force-stop` its own process); see the
        // class doc and the report for what that does and does not prove.
        abandonAtOpenStream() // death during the copy: no verified copy exists yet
        abandonBeforeDelete() // death after the verified copy: duplicate, never loss
    }

    private suspend fun abandonAtOpenStream() {
        val batch = BatchId("ac5-during-copy")
        val graph = deathGraph(dieOnOpenStream = 1, dieOnDelete = Int.MAX_VALUE)
        val file = writeFile("during-copy.txt", payload(seed = 4))
        val sourceDigest = hasher.sha256(file.inputStream())

        val died = runCatching {
            graph.gateway.quarantine(listOf(item(file, sourceId())), batch)
        }
        assertTrue(
            "the coroutine must be abandoned, not complete",
            died.exceptionOrNull() is CancellationException,
        )
        assertPending(batch)

        val report = graph.reconciler.reconcile()

        val row = journal.byBatch(batch.value).single()
        assertEquals(
            "no quarantine copy exists and the original is intact, so the operation never ran",
            JournalState.ABORTED,
            row.state,
        )
        assertTrue("the original must be intact and unchanged", file.isFile)
        assertEquals(
            "the original bytes must be unchanged",
            sourceDigest,
            hasher.sha256(file.inputStream()),
        )
        assertEquals(
            "no quarantine copy may exist",
            0,
            graph.quarantineStore.scan().size,
        )
        assertRecoveryIsComplete(report)
    }

    private suspend fun abandonBeforeDelete() {
        val batch = BatchId("ac5-before-delete")
        val graph = deathGraph(dieOnOpenStream = Int.MAX_VALUE, dieOnDelete = 1)
        val file = writeFile("before-delete.txt", payload(seed = 5))
        val sourceDigest = hasher.sha256(file.inputStream())

        val died = runCatching {
            graph.gateway.quarantine(listOf(item(file, sourceId())), batch)
        }
        assertTrue(
            "the coroutine must be abandoned, not complete",
            died.exceptionOrNull() is CancellationException,
        )
        assertPending(batch)

        // The verified copy and sidecar survived the abandonment: a death here can
        // only duplicate, never lose, the bytes.
        val copies = graph.quarantineStore.scan().filter { it.sidecar.batchId == batch.value }
        assertEquals("the verified copy must have survived", 1, copies.size)
        assertEquals(
            "the surviving copy must hash to the source",
            sourceDigest,
            hasher.sha256(File(copies.single().path).inputStream()),
        )
        assertTrue("the original must still be there (a duplicate, not a loss)", file.isFile)

        val report = graph.reconciler.reconcile()

        val row = journal.byBatch(batch.value).single()
        assertEquals(
            "a verified copy in quarantine means the operation completed",
            JournalState.DONE,
            row.state,
        )
        assertTrue("the original duplicate must still exist", file.isFile)
        assertTrue("the verified quarantine copy must still exist", File(copies.single().path).isFile)
        assertRecoveryIsComplete(report)
    }

    private suspend fun assertPending(batch: BatchId) {
        val rows = journal.byBatch(batch.value)
        assertEquals(
            "the write-ahead row must be committed before the operation",
            1,
            rows.size,
        )
        assertEquals(
            "an abandoned operation leaves its row PENDING until reconciliation",
            JournalState.PENDING,
            rows.single().state,
        )
    }

    /**
     * AC-5's invariant: reconciliation leaves every row terminal (nothing
     * `PENDING`/`FAILED`) or reported as unknown, and it reports each unresolved
     * row it classified.
     */
    private suspend fun assertRecoveryIsComplete(report: com.swipedelete.core.model.RecoveryReport) {
        assertTrue(
            "reconciliation must report every row it classified",
            report.resolved.size == 1,
        )
        assertTrue(
            "these deterministic scenarios must classify fully, not report unknown",
            report.unknown.isEmpty(),
        )
        assertTrue(
            "reconciliation must leave no PENDING/FAILED row unresolved",
            journal.unresolved().isEmpty(),
        )
        assertTrue(
            "every reconciled row must be terminal",
            journal.page(100, 0).all {
                it.state == JournalState.DONE || it.state == JournalState.ABORTED
            },
        )
    }

    // --- AC-1 ------------------------------------------------------------------

    @Test
    fun everyMutationProducedExactlyOneJournalRowWithATerminalState() = runBlocking<Unit> {
        val graph = newGraph()
        val sourceId = sourceId()
        val first = writeFile("one.txt", payload(seed = 6))
        val second = writeFile("two.txt", payload(seed = 7))

        val batch = BatchId("ac1-batch")
        val quarantined = graph.gateway.quarantine(
            listOf(item(first, sourceId), item(second, sourceId)),
            batch,
        )
        val entries = (quarantined as QuarantineResult.Quarantined).entries

        val quarantineRows = journal.byBatch(batch.value)
        assertEquals(
            "one mutation of two items writes exactly one row per item",
            2,
            quarantineRows.size,
        )
        assertTrue(
            "every quarantine row must be terminal DONE with no error",
            quarantineRows.all {
                it.opKind == OpKind.QUARANTINE &&
                    it.state == JournalState.DONE &&
                    it.error == null
            },
        )

        assertEquals(
            RestoreResult.Restored(first.absolutePath),
            graph.gateway.restore(entries.first()),
        )

        val allRows = journal.page(100, 0)
        val restoreRows = allRows.filter { it.opKind == OpKind.RESTORE }
        assertEquals("one restore mutation writes exactly one row", 1, restoreRows.size)
        assertEquals(
            "the restore row must be terminal DONE",
            JournalState.DONE,
            restoreRows.single().state,
        )
        assertEquals(
            "exactly the two mutations were journalled",
            3,
            allRows.size,
        )
        assertTrue(
            "no row may be left PENDING/FAILED unreported",
            journal.unresolved().isEmpty(),
        )
    }

    // --- AC-7 ------------------------------------------------------------------

    @Test
    fun aProtectedRootItemProducesZeroJournalRowsAndZeroMoves() = runBlocking<Unit> {
        val graph = newGraph()
        val sourceId = sourceId()
        val bytes = payload(seed = 8)
        // A real file inside a real `DCIM/Camera` directory under the granted root.
        val protectedFile = writeFile("DCIM/Camera/holiday.txt", bytes)
        val digestBefore = hasher.sha256(protectedFile.inputStream())

        val result = graph.gateway.quarantine(
            listOf(item(protectedFile, sourceId)),
            BatchId("ac7"),
        )

        val partial = result as QuarantineResult.Partial
        assertTrue("a protected item may not be quarantined", partial.entries.isEmpty())
        assertEquals("exactly one refusal must be reported", 1, partial.failures.size)
        assertEquals(
            "the refusal reason must be protected_root",
            ProtectedRootsStorageGateway.REASON,
            partial.failures.single().reason,
        )

        assertTrue(
            "AC-7 is evaluated before AC-1: a protected item writes zero journal rows",
            journal.page(10, 0).isEmpty(),
        )
        assertTrue(
            "a protected item must not be copied into quarantine",
            graph.quarantineStore.scan().isEmpty(),
        )
        assertTrue("the protected file must still exist", protectedFile.isFile)
        assertEquals(
            "the protected file must be byte-identical",
            digestBefore,
            hasher.sha256(protectedFile.inputStream()),
        )
    }

    // --- Wiring ----------------------------------------------------------------

    private fun grantAllFilesAccess(): Boolean {
        val output = runCatching {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow")
                .use { descriptor ->
                    ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().readText()
                }
        }.getOrElse { "shell-command-failed: $it" }
        val granted = Environment.isExternalStorageManager()
        android.util.Log.i(TAG, "appops grant output='${output.trim()}' isExternalStorageManager=$granted")
        return granted
    }

    private fun newGraph(concrete: StorageGateway = rawGateway()): StorageGraph =
        assembleStorageGraph(
            concrete = concrete,
            journal = journal,
            quarantineRoot = quarantineRoot,
            hasher = hasher,
            clock = clock,
            exists = { path -> File(path).exists() },
        )

    /**
     * The real raw gateway over the real root, wrapped so the coroutine can be
     * abandoned at a chosen **real** filesystem operation. `RawStorageGateway`
     * always gets `hasAllFilesAccess = true`: this is a deliberate Tier-2 test
     * harness, and the OS-level grant is recorded separately (see [setUp]).
     */
    private fun deathGraph(dieOnOpenStream: Int, dieOnDelete: Int): StorageGraph {
        val concrete = DeathAtRealFileOperation(
            delegate = rawGateway(),
            dieOnOpenStream = dieOnOpenStream,
            dieOnDelete = dieOnDelete,
        )
        return newGraph(concrete)
    }

    private fun rawGateway(): RawStorageGateway =
        RawStorageGateway(roots = listOf(sourceRoot), hasAllFilesAccess = true)

    private suspend fun sourceId(): SourceId =
        rawGateway().sources().single().id

    private fun writeFile(relativePath: String, bytes: ByteArray): File {
        val file = File(sourceRoot, relativePath)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return file
    }

    private fun item(file: File, sourceId: SourceId): MediaItem = MediaItem(
        id = ItemId("${sourceId.value}:${file.name}"),
        uri = file.absolutePath,
        path = file.absolutePath,
        relativePath = file.name,
        mediaStoreId = null,
        mime = "text/plain",
        sizeBytes = file.length(),
        width = null,
        height = null,
        durationMs = null,
        dateAdded = 0L,
        dateModified = file.lastModified(),
        bucket = null,
        sourceId = sourceId,
    )

    /** ~150 KiB of deterministic ASCII — real bytes, not a media file. */
    private fun payload(seed: Int): ByteArray =
        buildString {
            repeat(4_000) {
                append("line ").append(it).append(" seed ").append(seed)
                    .append(" of the M0 acceptance payload\n")
            }
        }.toByteArray(Charsets.UTF_8)

    /** An abandoned coroutine, signalled the way the host chaos sweep does. */
    private class ProcessDeathException(message: String) : CancellationException(message)

    /**
     * Delegates every call to a real gateway and abandons the coroutine at the
     * chosen real filesystem operation by throwing [ProcessDeathException]. It
     * never fabricates an operation; it only decides when the process "dies".
     */
    private class DeathAtRealFileOperation(
        private val delegate: StorageGateway,
        private val dieOnOpenStream: Int,
        private val dieOnDelete: Int,
    ) : StorageGateway by delegate {

        private var opens = 0
        private var deletes = 0

        override fun openStream(item: MediaItem): InputStream {
            opens++
            if (opens == dieOnOpenStream) {
                throw ProcessDeathException("abandoned during the copy (openStream #$opens)")
            }
            return delegate.openStream(item)
        }

        override suspend fun deletePermanently(items: List<MediaItem>): DeleteResult {
            deletes++
            if (deletes == dieOnDelete) {
                throw ProcessDeathException("abandoned before the original delete (#$deletes)")
            }
            return delegate.deletePermanently(items)
        }
    }

    private companion object {
        const val TAG: String = "M0Acceptance"
        const val DB_NAME: String = "m0-acceptance.db"
        const val SHARED_DIR: String = "SwipeDeleteM0Acceptance"
        const val APP_EXTERNAL_DIR: String = "m0-acceptance"
    }
}
