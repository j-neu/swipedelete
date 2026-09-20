package com.swipedelete.core.storage.quarantine

import com.swipedelete.core.common.Clock
import com.swipedelete.core.common.Hasher
import com.swipedelete.core.common.Sha256Hasher
import com.swipedelete.core.model.BatchId
import com.swipedelete.core.model.ItemId
import com.swipedelete.core.model.JournalEntry
import com.swipedelete.core.model.MediaItem
import com.swipedelete.core.model.SourceId
import com.swipedelete.core.storage.QuarantineResult
import com.swipedelete.core.storage.QuarantineStorageGateway
import com.swipedelete.core.storage.RestoreResult
import com.swipedelete.core.storage.fake.FakeStorageGateway
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * AC-3: a delete moves the bytes into app-managed quarantine, restorable for 30
 * days with hash verification. AC-15: a cancelled or failed copy leaves the
 * original untouched. AC-17: restore never overwrites an existing file.
 *
 * The source bytes are supplied by [FakeStorageGateway] (registered content);
 * the quarantine root and the restore destination are real temporary
 * directories, so the store's copy → verify → delete and its sidecar are
 * exercised against the real filesystem.
 */
class QuarantineRestoreTest {

    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun cleanUp() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun `quarantine then restore returns byte-identical content to the original path`() = runTest {
        val h = newHarness()
        val original = "GIF89a-not-really-a-gif".toByteArray()
        val item = h.item("Memes/a.gif", original)

        val result = h.gateway.quarantine(listOf(item), BatchId("b1"))

        val quarantined = result as? QuarantineResult.Quarantined
            ?: fail("expected a full quarantine but was $result")
        val entry = quarantined.entries.single()
        assertFalse(h.fake.fileExists(item.path!!), "the source must be gone after quarantine")
        val quarantinedFile = File(entry.toUri!!)
        assertTrue(quarantinedFile.isFile, "the quarantined copy must exist")
        assertTrue(QuarantineLayout.sidecarFor(quarantinedFile).isFile, "a sidecar must exist beside it")

        val restore = h.gateway.restore(entry)

        assertTrue(restore is RestoreResult.Restored, "expected Restored but was $restore")
        assertContentEquals(original, File(item.path!!).readBytes())
    }

    @Test
    fun `restore verifies the recorded hash and refuses on mismatch`() = runTest {
        val h = newHarness()
        val original = "original bytes".toByteArray()
        val item = h.item("Memes/a.gif", original)
        val entry = h.quarantine(item)
        val quarantined = File(entry.toUri!!)
        val expected = SidecarCodec.decode(QuarantineLayout.sidecarFor(quarantined).readText()).sha256

        quarantined.writeBytes("tampered bytes".toByteArray())
        val actual = sha256Hex("tampered bytes".toByteArray())

        val restore = h.gateway.restore(entry)

        val mismatch = restore as? RestoreResult.HashMismatch
            ?: fail("expected HashMismatch but was $restore")
        assertEquals(expected, mismatch.expected)
        assertEquals(actual, mismatch.actual)
        assertNotEquals(mismatch.expected, mismatch.actual)
        assertFalse(File(item.path!!).exists(), "the original path must stay empty")
        assertTrue(quarantined.isFile, "the quarantined file must be kept, never dropped")
    }

    @Test
    fun `restore into an occupied path reports Conflict rather than overwriting`() = runTest {
        val h = newHarness()
        val item = h.item("Memes/a.gif", "quarantined".toByteArray())
        val entry = h.quarantine(item)
        val quarantined = File(entry.toUri!!)
        val occupant = File(item.path!!).apply {
            parentFile!!.mkdirs()
            writeBytes("occupant".toByteArray())
        }

        val restore = h.gateway.restore(entry)

        assertTrue(restore is RestoreResult.Conflict, "expected Conflict but was $restore")
        assertContentEquals("occupant".toByteArray(), occupant.readBytes(), "the occupant must be untouched")
        assertTrue(quarantined.isFile, "the quarantined copy must survive a conflict")
    }

    @Test
    fun `insufficient free space is reported never silently downgraded`() = runTest {
        val h = newHarness()
        val item = h.item("Videos/big.mp4", ByteArray(200) { it.toByte() })
        h.fake.capacityFreeBytes = 50L

        val result = h.gateway.quarantine(listOf(item), BatchId("b1"))

        val insufficient = result as? QuarantineResult.InsufficientSpace
            ?: fail("expected InsufficientSpace but was $result")
        assertEquals(200L, insufficient.neededBytes)
        assertEquals(50L, insufficient.freeBytes)
        assertTrue(h.fake.fileExists(item.path!!), "nothing may move when space is short")
        assertFalse(File(h.root, "b1").exists(), "nothing may be written under the batch directory")
    }

    @Test
    fun `empty quarantine is refused without the explicit permanent flag`() = runTest {
        val h = newHarness()
        val entry = h.quarantine(h.item("Memes/a.gif", "bytes".toByteArray()))
        val quarantined = File(entry.toUri!!)
        val sidecar = QuarantineLayout.sidecarFor(quarantined)

        val refused = h.store.empty(BatchId("b1"), allowPermanent = false)

        val refusal = refused as? QuarantineResult.Partial
            ?: fail("expected a refusal but was $refused")
        assertTrue(refusal.failures.isNotEmpty(), "the refusal must carry an explicit reason")
        assertTrue(refusal.failures.all { it.reason.isNotBlank() })
        assertTrue(quarantined.isFile, "a refusal must delete nothing")
        assertTrue(sidecar.isFile, "a refusal must delete nothing")

        val emptied = h.store.empty(BatchId("b1"), allowPermanent = true)

        assertTrue(emptied is QuarantineResult.Quarantined, "expected the batch to empty but was $emptied")
        assertFalse(quarantined.exists(), "the permitted permanent delete removes the file")
        assertFalse(sidecar.exists(), "the permitted permanent delete removes the sidecar")
    }

    @Test
    fun `a failed verification leaves the original untouched`() = runTest {
        val h = newHarness(hasher = WrongHasher())
        val item = h.item("Memes/a.gif", "content".toByteArray())

        val result = h.gateway.quarantine(listOf(item), BatchId("b1"))

        val partial = result as? QuarantineResult.Partial
            ?: fail("expected a per-item failure but was $result")
        assertTrue(partial.failures.single().reason.isNotBlank())
        assertTrue(h.fake.fileExists(item.path!!), "the original must be untouched")
        assertFalse(File(h.root, "b1/Memes/a.gif").exists(), "no partial copy may remain")
        assertFalse(QuarantineLayout.sidecarFor(File(h.root, "b1/Memes/a.gif")).exists(), "no sidecar for a failed copy")
    }

    @Test
    fun `quarantine writes the sidecar with the required fields`() = runTest {
        val h = newHarness()
        val original = "sidecar bytes".toByteArray()
        val item = h.item("Memes/a.gif", original, mediaStoreId = 42L)
        val entry = h.quarantine(item)

        val sidecar = SidecarCodec.decode(QuarantineLayout.sidecarFor(File(entry.toUri!!)).readText())

        assertEquals(item.uri, sidecar.originalUri)
        assertEquals(item.path, sidecar.originalPath)
        assertEquals(item.sizeBytes, sidecar.sizeBytes)
        assertEquals(item.mime, sidecar.mime)
        assertEquals(sha256Hex(original), sidecar.sha256)
        assertEquals("b1", sidecar.batchId)
        assertEquals(item.sourceId.value, sidecar.sourceId)
        assertEquals(42L, sidecar.mediaStoreId)
        assertTrue(sidecar.expiresAt > NOW, "expiry must be in the future")
    }

    @Test
    fun `contents lists the quarantined entries`() = runTest {
        val h = newHarness()
        val item = h.item("Memes/a.gif", "listed".toByteArray())
        val entry = h.quarantine(item)

        val listed = h.store.contents().first()

        assertEquals(1, listed.size)
        assertEquals(entry.toUri, listed.single().path)
        assertEquals(item.uri, listed.single().sidecar.originalUri)
        assertEquals(entry.id, listed.single().entry.id)
    }

    // --- Harness ---

    private inner class Harness(
        val base: File,
        val root: File,
        val fake: FakeStorageGateway,
        hasher: Hasher,
        clock: Clock,
    ) {
        val store = QuarantineStore(root, fake, hasher, clock)
        val gateway = QuarantineStorageGateway(fake, store)

        fun item(
            relative: String,
            content: ByteArray,
            mediaStoreId: Long? = null,
            mime: String = FakeStorageGateway.mimeFor(relative),
        ): MediaItem {
            val path = File(base, relative).absolutePath
            val item = MediaItem(
                id = ItemId("id:$relative"),
                uri = "content://swipedelete.test/$relative",
                path = path,
                relativePath = relative,
                mediaStoreId = mediaStoreId,
                mime = mime,
                sizeBytes = content.size.toLong(),
                width = null,
                height = null,
                durationMs = null,
                dateAdded = 0L,
                dateModified = DATE_MODIFIED,
                bucket = null,
                sourceId = SourceId("src-primary"),
                hash = null,
            )
            fake.register(item, content)
            return item
        }

        suspend fun quarantine(item: MediaItem): JournalEntry {
            val result = gateway.quarantine(listOf(item), BatchId("b1"))
            assertTrue(result is QuarantineResult.Quarantined, "harness quarantine failed: $result")
            return result.entries.single()
        }
    }

    private fun newHarness(
        hasher: Hasher = Sha256Hasher(),
        clock: Clock = FixedClock(NOW),
    ): Harness {
        val base = tempDir("base")
        val root = tempDir("quarantine")
        FakeStorageGateway().let { fake ->
            fake.addSource(
                com.swipedelete.core.model.SourceRef(
                    id = SourceId("src-primary"),
                    treeUri = base.absolutePath,
                    displayName = "Primary",
                    access = com.swipedelete.core.model.SourceRef.Access.RW,
                    kind = com.swipedelete.core.model.SourceKind.RAW,
                    itemCount = null,
                    totalBytes = null,
                    grantedAt = 0L,
                ),
            )
            return Harness(base, root, fake, hasher, clock)
        }
    }

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory("sd-quarantine-$prefix-").toFile().also { tempDirs += it }
}

private class FixedClock(private val now: Long) : Clock {
    override fun nowMillis(): Long = now
}

/** A hasher that returns a constant non-matching digest, forcing verification to fail. */
private class WrongHasher : Hasher {
    override suspend fun sha256(stream: InputStream): String {
        stream.use { it.readBytes() }
        return "0".repeat(64)
    }
}

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private const val NOW = 1_700_000_000_000L
private const val DATE_MODIFIED = 1_699_000_000_000L
