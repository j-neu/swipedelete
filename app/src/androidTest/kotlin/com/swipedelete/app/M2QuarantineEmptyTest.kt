package com.swipedelete.app

import android.content.Context
import android.os.StatFs
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.swipedelete.app.di.assembleStorageGraph
import com.swipedelete.core.common.Clock
import com.swipedelete.core.common.Sha256Hasher
import com.swipedelete.core.common.measureFreed
import com.swipedelete.core.database.SwipeDeleteDatabase
import com.swipedelete.core.model.BatchId
import com.swipedelete.core.model.ItemId
import com.swipedelete.core.model.JournalState
import com.swipedelete.core.model.MediaItem
import com.swipedelete.core.model.OpKind
import com.swipedelete.core.storage.QuarantineResult
import com.swipedelete.core.storage.quarantine.EmptyOutcome
import com.swipedelete.core.storage.quarantine.QuarantineAdmin
import com.swipedelete.core.storage.raw.RawStorageGateway
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random

/**
 * M2-T05 device check (AC-9): Empty really removes the quarantined bytes, the
 * OS-measured free space rises by about that much, and the journal records it.
 *
 * Every file is created by this test inside the app's own external-files
 * directory and deleted afterwards; no user storage is read or written, and no
 * All Files Access is requested.
 */
@RunWith(AndroidJUnit4::class)
class M2QuarantineEmptyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clock = object : Clock { override fun nowMillis() = System.currentTimeMillis() }
    private lateinit var db: SwipeDeleteDatabase
    private lateinit var runRoot: File

    @Before
    fun setUp() {
        runRoot = File(context.getExternalFilesDir(null) ?: context.filesDir, "m2-empty-${System.nanoTime()}")
        File(runRoot, "source").mkdirs()
        File(runRoot, "quarantine").mkdirs()
        context.deleteDatabase(DB)
        db = Room.databaseBuilder(context, SwipeDeleteDatabase::class.java, DB).build()
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(DB)
        runRoot.deleteRecursively()
    }

    @Test
    fun emptyRemovesTheBytesAndTheMeasuredFreeSpaceRises() = runBlocking<Unit> {
        val source = File(runRoot, "source")
        val raw = RawStorageGateway(roots = listOf(source), hasAllFilesAccess = true)
        val graph = assembleStorageGraph(
            concrete = raw, journal = db.journalDao(), quarantineRoot = File(runRoot, "quarantine"),
            hasher = Sha256Hasher(), clock = clock, exists = { File(it).exists() },
        )
        val sourceId = raw.sources().single().id
        val files = (1..3).map { n ->
            File(source, "f$n.bin").also { it.writeBytes(Random(n).nextBytes(4 * 1024 * 1024)) }
        }
        val items = files.map { f ->
            MediaItem(
                id = ItemId("${sourceId.value}:${f.name}"), uri = f.absolutePath, path = f.absolutePath,
                relativePath = f.name, mediaStoreId = null, mime = "application/octet-stream",
                sizeBytes = f.length(), width = null, height = null, durationMs = null, dateAdded = 0L,
                dateModified = f.lastModified(), bucket = null, sourceId = sourceId,
            )
        }
        assertTrue(graph.gateway.quarantine(items, BatchId("m2")) is QuarantineResult.Quarantined)
        val admin = QuarantineAdmin(graph.quarantineStore, graph.gateway, db.journalDao(), clock)
        assertEquals(3, admin.contents().size)

        assertEquals(EmptyOutcome.Refused, admin.emptyAll(allowPermanent = false))
        assertEquals("a refused Empty leaves everything", 3, admin.contents().size)

        val probe = runRoot.absolutePath
        val before = StatFs(probe).availableBytes
        val outcome = admin.emptyAll(allowPermanent = true)
        val after = StatFs(probe).availableBytes
        val measured = measureFreed(before, after, expectedBytes = items.sumOf { it.sizeBytes })
        Log.i("M2Empty", "empty outcome=$outcome expected=${measured.expectedBytes} rawDelta=${measured.rawDeltaBytes}")

        assertEquals(EmptyOutcome.Emptied(count = 3, bytes = measured.expectedBytes), outcome)
        assertEquals(0, admin.contents().size)
        assertTrue("measured freed must be positive", measured.freedBytes > 0)
        val purge = db.journalDao().page(100, 0).filter { it.opKind == OpKind.DELETE_PERMANENT }
        assertEquals(3, purge.size)
        assertTrue(purge.all { it.state == JournalState.DONE })
    }

    private companion object {
        const val DB = "m2-empty-test.db"
    }
}
