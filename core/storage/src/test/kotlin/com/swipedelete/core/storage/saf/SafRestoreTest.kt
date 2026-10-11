package com.swipedelete.core.storage.saf

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.swipedelete.core.common.Clock
import com.swipedelete.core.common.Sha256Hasher
import com.swipedelete.core.model.BatchId
import com.swipedelete.core.model.Capacity
import com.swipedelete.core.model.JournalEntry
import com.swipedelete.core.model.MediaItem
import com.swipedelete.core.model.SourceId
import com.swipedelete.core.model.SourceKind
import com.swipedelete.core.model.SourceRef
import com.swipedelete.core.storage.OriginalRestorer
import com.swipedelete.core.storage.QuarantineResult
import com.swipedelete.core.storage.QuarantineStorageGateway
import com.swipedelete.core.storage.RestoreResult
import com.swipedelete.core.storage.StorageGateway
import com.swipedelete.core.storage.quarantine.QuarantineStore
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AC-3 / AC-17 for a folder-grant (SAF) item: what the deck quarantines must be
 * restorable to where it came from, verified, and never over another file.
 *
 * A SAF original has no raw path to write back to, so the quarantine store
 * hands the job to the concrete gateway ([com.swipedelete.core.storage.OriginalRestorer]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafRestoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val treeUri = "content://com.swipedelete.test/tree/root"
    private val tree = FakeDocumentTree(treeUri = treeUri, rootDocumentId = "root")
    private val quarantineRoot: File = Files.createTempDirectory("saf-restore-").toFile()
    private var granted = true

    private val source = SourceRef(
        id = SourceId("src-saf"),
        treeUri = treeUri,
        displayName = "Memes",
        access = SourceRef.Access.RW,
        kind = SourceKind.SAF,
        itemCount = null,
        totalBytes = null,
        grantedAt = 0L,
    )

    private val gateway: StorageGateway = run {
        val saf = SafStorageGateway(
            context = context,
            persisted = object : PersistedGrantStore {
                override suspend fun sources(): List<SourceRef> = if (granted) listOf(source) else emptyList()
            },
            access = tree,
        )
        // Robolectric's StatFs reports no free space, which would stop the
        // pre-flight; everything else is the real SAF gateway.
        val concrete = object : StorageGateway by saf, OriginalRestorer by saf {
            override suspend fun capacity(): Capacity =
                Capacity(totalBytes = GIGABYTE, usedBytes = 0L, freeBytes = GIGABYTE, reclaimableBytes = 0L)
        }
        val store = QuarantineStore(
            root = quarantineRoot,
            gateway = concrete,
            hasher = Sha256Hasher(),
            clock = object : Clock {
                override fun nowMillis(): Long = 1_700_000_000_000L
            },
        )
        QuarantineStorageGateway(concrete, store)
    }

    private suspend fun itemNamed(relativePath: String): MediaItem =
        gateway.enumerate(source, null).toList().flatMap { it.items }.first { it.relativePath == relativePath }

    private suspend fun quarantine(relativePath: String): JournalEntry {
        val result = gateway.quarantine(listOf(itemNamed(relativePath)), BatchId("b1"))
        return assertIs<QuarantineResult.Quarantined>(result, "setup: quarantine failed: $result").entries.single()
    }

    @Test
    fun `a quarantined folder-grant item is restored byte-identical to its original place`() = runTest {
        val bytes = "GIF89a-original".toByteArray()
        tree.addFile("root", "root/a.gif", "a.gif", content = bytes)
        val entry = quarantine("a.gif")
        assertNull(tree.document("root/a.gif"), "setup: the original must be gone after quarantine")

        val result = gateway.restore(entry)

        assertIs<RestoreResult.Restored>(result, "was $result")
        assertContentEquals(bytes, tree.contentOf("root/a.gif"))
        assertTrue(!File(entry.toUri!!).exists(), "a restored item leaves quarantine")
    }

    @Test
    fun `restore recreates a folder that was removed in the meantime`() = runTest {
        val album = tree.addDirectory("root", "root/album", "album")
        tree.addFile("root/album", "root/album/a.gif", "a.gif", content = "bytes".toByteArray())
        val entry = quarantine("album/a.gif")
        tree.delete(album)

        val result = gateway.restore(entry)

        assertIs<RestoreResult.Restored>(result, "was $result")
        assertContentEquals("bytes".toByteArray(), tree.contentOf("root/album/a.gif"))
    }

    @Test
    fun `restore never overwrites a file that took the original name`() = runTest {
        tree.addFile("root", "root/a.gif", "a.gif", content = "original".toByteArray())
        val entry = quarantine("a.gif")
        tree.addFile("root", "root/a.gif", "a.gif", content = "newcomer".toByteArray())

        val result = gateway.restore(entry)

        assertIs<RestoreResult.Conflict>(result, "was $result")
        assertContentEquals("newcomer".toByteArray(), tree.contentOf("root/a.gif"), "the occupant is untouched")
        assertTrue(File(entry.toUri!!).isFile, "the quarantine copy is kept for a later restore")
    }

    @Test
    fun `restore without the folder grant is refused and keeps the quarantine copy`() = runTest {
        tree.addFile("root", "root/a.gif", "a.gif", content = "original".toByteArray())
        val entry = quarantine("a.gif")
        granted = false

        val result = gateway.restore(entry)

        val conflict = assertIs<RestoreResult.Conflict>(result, "was $result")
        assertEquals("source_not_granted", conflict.occupiedBy)
        assertNotNull(File(entry.toUri!!).takeIf { it.isFile }, "the quarantine copy is kept")
    }

    private companion object {
        const val GIGABYTE = 1L shl 30
    }
}
