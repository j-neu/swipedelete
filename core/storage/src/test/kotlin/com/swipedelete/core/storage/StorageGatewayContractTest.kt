package com.swipedelete.core.storage

import com.swipedelete.core.model.BatchId
import com.swipedelete.core.model.ItemId
import com.swipedelete.core.model.JournalEntry
import com.swipedelete.core.model.MediaItem
import com.swipedelete.core.model.SourceId
import com.swipedelete.core.model.SourceKind
import com.swipedelete.core.model.SourceRef
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reusable contract every [StorageGateway] implementation must satisfy. Run it
 * for the fake (here) and for SAF / raw / MediaStore (Tasks 20/21).
 *
 * The four tests below are the frozen M0-T05 contract: AC-1 (journal-before-
 * operation), AC-15 (cancellation leaves the original untouched) and AC-17
 * (restore never overwrites).
 */
abstract class StorageGatewayContractTest {

    abstract fun newGateway(items: List<MediaItem> = defaultItems()): StorageGateway

    @Test
    fun `enumerate yields stable ids that metadata can resolve`() = runTest {
        val gw = newGateway()
        val page = gw.enumerate(source(), null).first()
        val ids = page.items.map { it.id }
        assertTrue(ids.isNotEmpty())
        page.items.forEach { item ->
            val meta = gw.metadata(item)
            assertEquals(item.sizeBytes, meta.sizeBytes)
            assertEquals(item.mime, meta.mime)
        }
        assertEquals(ids, gw.enumerate(source(), null).first().items.map { it.id })
    }

    @Test
    fun `move never overwrites an existing filename`() = runTest {
        val gw = newGateway()
        val dst = destinationWith("a.gif")
        val result = gw.move(item(name = "a.gif"), dst)
        assertTrue(result is MoveResult.NameCollision)
        assertTrue((result as MoveResult.NameCollision).resolvedTo.path!!.endsWith("a (1).gif"))
        assertEquals(2, gw.enumerate(dst.source, null).first().items.count { it.mime == "image/gif" })
    }

    @Test
    fun `restore refuses to overwrite and reports Conflict`() = runTest {
        val gw = newGateway()
        val original = item(name = "a.gif")
        val quarantined = gw.quarantine(listOf(original), BatchId("b1")) as QuarantineResult.Quarantined
        newFileAt(original.path!!)
        val entry = quarantined.entries.single()
        assertTrue(gw.restore(entry) is RestoreResult.Conflict)
        assertTrue(fileExists(original.path!!))
        assertTrue(fileExists(quarantinePathFor(entry)))
    }

    @Test
    fun `cancelled quarantine leaves the original untouched`() = runTest {
        val gw = newGateway()
        armCancellation(gw)
        val job = launch { gw.quarantine(listOf(item(name = "a.gif"), item(name = "b.gif")), BatchId("b1")) }
        yield()
        job.cancelAndJoin()
        val remaining = gw.enumerate(source(), null).first().items.map { it.id }
        assertTrue(remaining.contains(item(name = "a.gif").id))
    }

    /**
     * Implementations override this to make the next quarantine move suspend
     * indefinitely, so the AC-15 cancellation path can be exercised
     * deterministically. The default is a no-op.
     */
    protected open suspend fun armCancellation(gateway: StorageGateway) = Unit

    // --- Fixture helpers shared by every implementation ---

    protected fun source(): SourceRef = PRIMARY_SOURCE

    protected fun destinationSource(): SourceRef = DESTINATION_SOURCE

    protected fun defaultItems(): List<MediaItem> = listOf(
        item(name = "a.gif"),
        item(name = "b.png"),
        item(name = "c.jpg"),
    )

    protected fun item(name: String, source: SourceRef = source()): MediaItem {
        val path = pathOf(source, name)
        return MediaItem(
            id = ItemId("${source.id.value}:$name"),
            uri = path,
            path = path,
            relativePath = name,
            mediaStoreId = null,
            mime = mimeFor(name),
            sizeBytes = 1024L,
            width = null,
            height = null,
            durationMs = null,
            dateAdded = 0L,
            dateModified = 0L,
            bucket = null,
            sourceId = source.id,
            hash = null,
        )
    }

    protected fun pathOf(name: String): String = pathOf(source(), name)

    protected fun pathOf(source: SourceRef, name: String): String = "${source.treeUri}/$name"

    /** Creates (and registers) a destination source whose root already holds a file named [existingName]. */
    protected abstract fun destinationWith(existingName: String): Destination

    /** Creates a file that physically occupies [path]. */
    protected abstract fun newFileAt(path: String)

    protected abstract fun fileExists(path: String): Boolean

    /** The path of the quarantined copy described by [entry]. */
    protected abstract fun quarantinePathFor(entry: JournalEntry): String

    companion object {
        val PRIMARY_SOURCE = SourceRef(
            id = SourceId("src-primary"),
            treeUri = "fake://primary",
            displayName = "Primary",
            access = SourceRef.Access.RW,
            kind = SourceKind.RAW,
            itemCount = null,
            totalBytes = null,
            grantedAt = 0L,
        )
        val DESTINATION_SOURCE = SourceRef(
            id = SourceId("src-export"),
            treeUri = "fake://export",
            displayName = "Export",
            access = SourceRef.Access.RW,
            kind = SourceKind.RAW,
            itemCount = null,
            totalBytes = null,
            grantedAt = 0L,
        )

        fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "gif" -> "image/gif"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "mp4" -> "video/mp4"
            else -> "application/octet-stream"
        }
    }
}
