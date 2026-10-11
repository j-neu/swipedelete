package com.swipedelete.core.storage

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
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * AC-7: protected roots cannot be modified by any bulk flow. The decorator
 * refuses protected items *before* the delegate is called, so a refused item
 * can never reach a journal write (the frozen order is
 * `ProtectedRoots → Journaling → Quarantine → Concrete`).
 */
class ProtectedRootsTest {

    private val gateway = ProtectedRootsStorageGateway(
        delegate = FakeStorageGateway(),
        unlocked = { emptySet() },
        quarantineRoot = QUARANTINE_ROOT,
        now = { NOW },
    )

    @Test
    fun `DCIM items are refused bulk modification`() = runTest {
        val result = gateway.quarantine(listOf(item(path = "$DCIM/Camera/IMG_1.jpg")), BatchId("b"))

        assertTrue(result is QuarantineResult.Partial)
        val failure = result.failures.single()
        assertEquals("protected_root", failure.reason)
        assertEquals("DCIM/", failure.cause)
    }

    @Test
    fun `a bucket named Screenshots is protected even outside Pictures`() {
        assertTrue(gateway.isProtected(item(relativePath = "Somewhere/Screenshots/x.png")))
    }

    @Test
    fun `the quarantine directory is never a delete target`() {
        assertTrue(gateway.isProtected(item(path = "$QUARANTINE_ROOT/b1/a.jpg")))
    }

    @Test
    fun `an unlocked pattern is allowed and every other pattern stays protected`() = runTest {
        // The brief's `DCIM/Camera/a.jpg` is protected by the `Camera` bucket
        // heuristic as well as by `DCIM/`, so unlocking `DCIM/` alone can never
        // yield Quarantined. This item matches only the unlocked `DCIM/` pattern.
        val unlockedItem = item(path = "$DCIM/Album/a.jpg")
        val delegate = FakeStorageGateway(listOf(unlockedItem))
        val unlocked = ProtectedRootsStorageGateway(
            delegate = delegate,
            unlocked = { setOf("DCIM/") },
            now = { NOW },
        )

        assertTrue(unlocked.quarantine(listOf(unlockedItem), BatchId("b")) is QuarantineResult.Quarantined)

        assertTrue(unlocked.quarantine(listOf(item(path = "Pictures/a.jpg")), BatchId("b")) is QuarantineResult.Partial)
    }

    @Test
    fun `a segment that merely starts with a pattern is not protected`() {
        assertFalse(gateway.isProtected(item(path = "/storage/emulated/0/PicturesofYou/x.jpg")))
    }

    @Test
    fun `matching is case-insensitive`() {
        assertTrue(gateway.isProtected(item(path = "dcim/camera/img_1.jpg")))
    }

    @Test
    fun `a multi-segment pattern matches contiguously at depth`() {
        assertTrue(gateway.isProtected(item(path = "/storage/emulated/0/DCIM/Camera/sub/deep.jpg")))
        assertTrue(PathPatternMatcher.pathMatches("/a/DCIM/Camera/sub/deep.jpg", "DCIM/Camera"))
        assertFalse(PathPatternMatcher.pathMatches("/a/DCIM/x/Camera/sub/deep.jpg", "DCIM/Camera"))
    }

    @Test
    fun `a camera-roll bucket is protected even when the path is unremarkable`() {
        assertTrue(
            gateway.isProtected(
                item(path = "/storage/emulated/0/Misc/x.jpg", bucket = "WhatsApp Images"),
            ),
        )
    }

    @Test
    fun `media older than five years is protected and non-media of the same age is not`() {
        assertTrue(
            gateway.isProtected(
                item(path = "/storage/emulated/0/Misc/old.jpg", mime = "image/jpeg", dateModified = NOW - SIX_YEARS),
            ),
        )
        assertFalse(
            gateway.isProtected(
                item(path = "/storage/emulated/0/Misc/old.pdf", mime = "application/pdf", dateModified = NOW - SIX_YEARS),
            ),
        )
    }

    @Test
    fun `the granted SAF root itself is protected`() {
        val root = item(path = "/storage/emulated/0")
        val gateway = ProtectedRootsStorageGateway(
            delegate = FakeStorageGateway(),
            unlocked = { emptySet() },
            isRoot = { it.id == root.id },
            now = { NOW },
        )

        assertTrue(gateway.isProtected(root))
    }

    @Test
    fun `a fully refused batch never reaches the delegate`() = runTest {
        val delegate = CountingGateway()
        val gateway = ProtectedRootsStorageGateway(delegate, unlocked = { emptySet() }, now = { NOW })

        val quarantine = gateway.quarantine(listOf(item(path = "$DCIM/a.jpg")), BatchId("b"))
        assertEquals(0, delegate.quarantineCalls)

        val delete = gateway.deletePermanently(listOf(item(path = "$DCIM/a.jpg")))
        assertEquals(0, delegate.deleteCalls)

        assertTrue(quarantine is QuarantineResult.Partial)
        assertTrue(delete is DeleteResult.Partial)
    }

    @Test
    fun `only the allowed items reach the delegate and the refusals are merged`() = runTest {
        val allowed = item(path = "/storage/emulated/0/Misc/a.jpg")
        val refused = item(path = "$DCIM/b.jpg")
        val delegate = CountingGateway()
        val gateway = ProtectedRootsStorageGateway(delegate, unlocked = { emptySet() }, now = { NOW })

        val result = gateway.quarantine(listOf(allowed, refused), BatchId("b"))

        assertTrue(result is QuarantineResult.Partial)
        assertEquals(listOf(allowed.id), delegate.receivedQuarantineIds)
        assertEquals(1, result.entries.size)
        val failure = result.failures.single()
        assertEquals(refused.id, failure.itemId)
        assertEquals("protected_root", failure.reason)
        assertEquals("DCIM/", failure.cause)
    }

    @Test
    fun `a protected item cannot be moved`() = runTest {
        val result = gateway.move(item(path = "$DCIM/a.jpg"), destination())

        assertTrue(result is MoveResult.Failed)
        assertEquals("protected_root", result.reason)
    }

    @Test
    fun `restore is not blocked by protected roots`() = runTest {
        val quarantinedPath = "fake://quarantine/b1/a.jpg"
        val originalPath = "$DCIM/Camera/a.jpg"
        val quarantined = item(path = quarantinedPath)
        val delegate = FakeStorageGateway(listOf(quarantined))
        val gateway = ProtectedRootsStorageGateway(
            delegate = delegate,
            unlocked = { emptySet() },
            quarantineRoot = QUARANTINE_ROOT,
            now = { NOW },
        )
        val entry = JournalEntry(
            id = "j1",
            batchId = BatchId("b1"),
            sessionId = null,
            itemId = quarantined.id,
            opKind = OpKind.QUARANTINE,
            fromUri = originalPath,
            toUri = quarantinedPath,
            hash = null,
            sizeBytes = quarantined.sizeBytes,
            state = JournalState.DONE,
            createdAt = 0L,
            expiresAt = 0L,
        )

        assertTrue(gateway.restore(entry) is RestoreResult.Restored)
        assertTrue(delegate.fileExists(originalPath))
    }

    @Test
    fun `read-only calls pass through to the delegate`() = runTest {
        val delegate = FakeStorageGateway()
        val gateway = ProtectedRootsStorageGateway(delegate, unlocked = { emptySet() }, now = { NOW })

        assertEquals(delegate.sources(), gateway.sources())
        assertEquals(delegate.capacity(), gateway.capacity())
    }

    // --- Items the user reviewed one at a time (the deck) ---

    @Test
    fun `an item the user reviewed one at a time may be quarantined from a protected folder`() = runTest {
        val reviewed = item(path = "$DCIM/Album/a.jpg")
        val delegate = CountingGateway()
        val gateway = ProtectedRootsStorageGateway(delegate, unlocked = { emptySet() }, now = { NOW })

        val result = withContext(IndividuallyReviewed(setOf(reviewed.id))) {
            gateway.quarantine(listOf(reviewed), BatchId("b"))
        }

        assertTrue(result is QuarantineResult.Quarantined)
        assertEquals(listOf(reviewed.id), delegate.receivedQuarantineIds)
    }

    @Test
    fun `the review exemption covers only the reviewed items of a batch`() = runTest {
        val reviewed = item(path = "$DCIM/Album/a.jpg")
        val other = item(path = "$DCIM/Album/b.jpg")
        val delegate = CountingGateway()
        val gateway = ProtectedRootsStorageGateway(delegate, unlocked = { emptySet() }, now = { NOW })

        val result = withContext(IndividuallyReviewed(setOf(reviewed.id))) {
            gateway.quarantine(listOf(reviewed, other), BatchId("b"))
        }

        assertTrue(result is QuarantineResult.Partial)
        assertEquals(listOf(reviewed.id), delegate.receivedQuarantineIds)
        assertEquals(other.id, result.failures.single().itemId)
    }

    @Test
    fun `a reviewed item is still refused inside the quarantine directory and at a granted root`() = runTest {
        val inQuarantine = item(path = "$QUARANTINE_ROOT/b1/a.jpg")
        val root = item(path = "/storage/emulated/0")
        val delegate = CountingGateway()
        val gateway = ProtectedRootsStorageGateway(
            delegate = delegate,
            unlocked = { emptySet() },
            isRoot = { it.id == root.id },
            quarantineRoot = QUARANTINE_ROOT,
            now = { NOW },
        )

        val result = withContext(IndividuallyReviewed(setOf(inQuarantine.id, root.id))) {
            gateway.quarantine(listOf(inQuarantine, root), BatchId("b"))
        }

        assertTrue(result is QuarantineResult.Partial)
        assertEquals(2, result.failures.size)
        assertEquals(0, delegate.quarantineCalls)
    }

    @Test
    fun `a reviewed item may be moved out of a protected folder`() = runTest {
        val reviewed = item(path = "$DCIM/Album/a.jpg")

        val result = withContext(IndividuallyReviewed(setOf(reviewed.id))) {
            gateway.move(reviewed, destination())
        }

        assertFalse(result == MoveResult.Failed("protected_root"))
    }

    @Test
    fun `permanent deletion is never exempt, reviewed or not`() = runTest {
        val reviewed = item(path = "$DCIM/Album/a.jpg")
        val delegate = CountingGateway()
        val gateway = ProtectedRootsStorageGateway(delegate, unlocked = { emptySet() }, now = { NOW })

        val result = withContext(IndividuallyReviewed(setOf(reviewed.id))) {
            gateway.deletePermanently(listOf(reviewed))
        }

        assertTrue(result is DeleteResult.Partial)
        assertEquals(0, delegate.deleteCalls)
    }

    // --- Folder-grant (SAF) items, whose path is an encoded document URI ---

    @Test
    fun `a folder-grant item under DCIM is recognised from its document uri`() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3ADCIM" +
            "/document/primary%3ADCIM%2FAlbum%2FIMG_1.jpg"

        assertTrue(gateway.isProtected(item(path = uri, relativePath = "Album/IMG_1.jpg")))
    }

    @Test
    fun `a folder-grant item outside every protected root is not protected`() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3AMemes" +
            "/document/primary%3AMemes%2Fa.gif"

        assertFalse(gateway.isProtected(item(path = uri, relativePath = "a.gif")))
    }
}

private class CountingGateway : StorageGateway by FakeStorageGateway() {
    var quarantineCalls = 0
        private set
    var deleteCalls = 0
        private set
    var receivedQuarantineIds: List<ItemId> = emptyList()
        private set

    override suspend fun quarantine(items: List<MediaItem>, batch: BatchId): QuarantineResult {
        quarantineCalls++
        receivedQuarantineIds = items.map { it.id }
        return QuarantineResult.Quarantined(batch, items.map { quarantinedEntry(it, batch) })
    }

    override suspend fun deletePermanently(items: List<MediaItem>): DeleteResult {
        deleteCalls++
        return DeleteResult.Deleted(items.size)
    }
}

private fun quarantinedEntry(item: MediaItem, batch: BatchId): JournalEntry = JournalEntry(
    id = "q:${batch.value}:${item.id.value}",
    batchId = batch,
    sessionId = null,
    itemId = item.id,
    opKind = OpKind.QUARANTINE,
    fromUri = item.uri,
    toUri = "fake://quarantine/${batch.value}/${item.id.value}",
    hash = null,
    sizeBytes = item.sizeBytes,
    state = JournalState.DONE,
    createdAt = 0L,
    expiresAt = 0L,
)

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

private fun item(
    path: String? = null,
    relativePath: String? = null,
    mime: String = "image/jpeg",
    bucket: String? = null,
    dateModified: Long = NOW,
): MediaItem {
    val key = path ?: relativePath ?: "anon"
    return MediaItem(
        id = ItemId("id:$key"),
        uri = path ?: relativePath ?: key,
        path = path,
        relativePath = relativePath,
        mediaStoreId = null,
        mime = mime,
        sizeBytes = 1024L,
        width = null,
        height = null,
        durationMs = null,
        dateAdded = 0L,
        dateModified = dateModified,
        bucket = bucket,
        sourceId = SourceId("src-primary"),
        hash = null,
    )
}

private const val NOW = 1_700_000_000_000L
private const val SIX_YEARS = 6L * 365 * 24 * 60 * 60 * 1000
private const val DCIM = "/storage/emulated/0/DCIM"
private const val QUARANTINE_ROOT = "/storage/emulated/0/Android/data/com.swipedelete.app/files/quarantine"
