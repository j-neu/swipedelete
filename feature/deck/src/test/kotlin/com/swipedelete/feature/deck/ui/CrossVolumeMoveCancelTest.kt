package com.swipedelete.feature.deck.ui

import com.swipedelete.core.model.ItemId
import com.swipedelete.core.model.MediaItem
import com.swipedelete.core.model.Slot
import com.swipedelete.core.model.SlotId
import com.swipedelete.core.model.SlotMode
import com.swipedelete.core.model.SourceId
import com.swipedelete.core.model.SourceKind
import com.swipedelete.core.model.SourceRef
import com.swipedelete.core.storage.fake.FakeStorageGateway
import com.swipedelete.feature.deck.domain.SlotMoveCoordinator
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield

/**
 * AC-15 (M1-T06): a cross-volume move is copy -> verify -> delete, and a
 * cancellation at any point leaves the original byte-identical. The test drives
 * the cancellation through [FakeStorageGateway]'s suspension seam, so it proves
 * the deck inherits the gateway's existing cancellation contract instead of
 * reimplementing a move.
 */
class CrossVolumeMoveCancelTest {

    private val source = SourceRef(
        id = SourceId("src-primary"),
        treeUri = "fake://primary",
        displayName = "Primary",
        access = SourceRef.Access.RW,
        kind = SourceKind.RAW,
        itemCount = null,
        totalBytes = null,
        grantedAt = 0L,
    )
    private val export = SourceRef(
        id = SourceId("src-export"),
        treeUri = "fake://export",
        displayName = "Export",
        access = SourceRef.Access.RW,
        kind = SourceKind.RAW,
        itemCount = null,
        totalBytes = null,
        grantedAt = 0L,
    )

    private fun gateway(): FakeStorageGateway = FakeStorageGateway().apply {
        addSource(source)
        addSource(export)
    }

    private fun item(path: String, id: String): MediaItem = MediaItem(
        id = ItemId(id),
        uri = path,
        path = path,
        relativePath = path.substringAfterLast('/'),
        mediaStoreId = null,
        mime = "image/gif",
        sizeBytes = 1024L,
        width = null,
        height = null,
        durationMs = null,
        dateAdded = 0L,
        dateModified = 0L,
        bucket = null,
        sourceId = source.id,
    )

    private fun moveSlot(): Slot = Slot(
        id = SlotId(1),
        name = "Memes",
        icon = "folder",
        colour = 0L,
        destinationUri = export.treeUri,
        mode = SlotMode.MOVE,
        sortOrder = 0,
    )

    @Test
    fun `cancelling the cross-volume copy leaves the original byte-identical`() = runTest {
        val gw = gateway()
        val original = item("fake://primary/a.gif", "i1")
        val bytes = "GIF89a-cross-volume-payload".toByteArray()
        gw.register(original, bytes)
        gw.beforeMove = { awaitCancellation() }

        val job = launch { SlotMoveCoordinator(gw).apply(moveSlot(), original) }
        yield()
        job.cancelAndJoin()

        assertTrue(gw.fileExists("fake://primary/a.gif"), "the original must still exist after cancellation")
        gw.openStream(original).use { stream ->
            assertContentEquals(bytes, stream.readBytes(), "the original bytes must be untouched (AC-15)")
        }
        assertFalse(gw.fileExists("fake://export/a.gif"), "a cancelled copy may leave no partial destination file")
    }

    @Test
    fun `cancelling at any point never loses or corrupts the original`() = runTest {
        for (stopAt in 0 until 8) {
            val gw = gateway()
            val original = item("fake://primary/a.gif", "i1")
            val bytes = "payload-$stopAt".toByteArray()
            gw.register(original, bytes)
            gw.beforeMove = { awaitCancellation() }

            val job = launch { SlotMoveCoordinator(gw).apply(moveSlot(), original) }
            repeat(stopAt) { yield() }
            job.cancelAndJoin()

            assertTrue(gw.fileExists("fake://primary/a.gif"), "stopAt=$stopAt: original missing")
            gw.openStream(original).use { stream ->
                assertContentEquals(bytes, stream.readBytes(), "stopAt=$stopAt: original corrupted")
            }
            assertFalse(gw.fileExists("fake://export/a.gif"), "stopAt=$stopAt: partial destination file")
        }
    }
}
