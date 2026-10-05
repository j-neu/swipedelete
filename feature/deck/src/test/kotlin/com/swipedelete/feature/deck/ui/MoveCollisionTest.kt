package com.swipedelete.feature.deck.ui

import com.swipedelete.core.model.ItemId
import com.swipedelete.core.model.MediaItem
import com.swipedelete.core.model.Slot
import com.swipedelete.core.model.SlotId
import com.swipedelete.core.model.SlotMode
import com.swipedelete.core.model.SourceId
import com.swipedelete.core.model.SourceKind
import com.swipedelete.core.model.SourceRef
import com.swipedelete.core.model.Tag
import com.swipedelete.core.storage.fake.FakeStorageGateway
import com.swipedelete.feature.deck.domain.SlotMoveCoordinator
import com.swipedelete.feature.deck.domain.SlotMoveOutcome
import com.swipedelete.feature.deck.domain.SlotTagSink
import com.swipedelete.feature.deck.session.MutationGate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * SPEC 5.3 / decision 4 (M1-T06): collisions never overwrite; the deck names the
 * renamed result but does not reimplement the rename. Every assertion runs
 * against the frozen [com.swipedelete.core.storage.StorageGateway] contract
 * through [SlotMoveCoordinator], so the ` (1)` / ` (2)` rule is the gateway's.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MoveCollisionTest {

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
    fun `a colliding name is renamed to (1) and never overwrites the destination`() = runTest {
        val gw = gateway()
        val original = item("fake://primary/a.gif", "i1")
        gw.register(original, "GIF89a-first".toByteArray())
        gw.createFileAt("fake://export/a.gif", mime = "image/gif")

        val result = SlotMoveCoordinator(gw).apply(moveSlot(), original)

        val renamed = assertIs<SlotMoveOutcome.Renamed>(
            result,
            "a taken destination name must be reported, not overwritten",
        )
        assertTrue(renamed.to.path!!.endsWith("a (1).gif"))
        assertTrue(gw.fileExists("fake://export/a.gif"), "the existing destination file must survive")
        assertTrue(gw.fileExists("fake://export/a (1).gif"))
        assertFalse(gw.fileExists("fake://primary/a.gif"))
    }

    @Test
    fun `a second collision becomes (2)`() = runTest {
        val gw = gateway()
        val first = item("fake://primary/a.gif", "i1")
        val second = item("fake://primary/other/a.gif", "i2")
        gw.register(first)
        gw.register(second)
        gw.createFileAt("fake://export/a.gif", mime = "image/gif")

        val coordinator = SlotMoveCoordinator(gw)
        val firstResult = coordinator.apply(moveSlot(), first)
        val secondResult = coordinator.apply(moveSlot(), second)

        val firstRenamed = assertIs<SlotMoveOutcome.Renamed>(firstResult)
        val secondRenamed = assertIs<SlotMoveOutcome.Renamed>(secondResult)
        assertTrue(firstRenamed.to.path!!.endsWith("a (1).gif"))
        assertTrue(secondRenamed.to.path!!.endsWith("a (2).gif"))
        assertTrue(gw.fileExists("fake://export/a.gif"))
        assertTrue(gw.fileExists("fake://export/a (1).gif"))
        assertTrue(gw.fileExists("fake://export/a (2).gif"))
    }

    @Test
    fun `the collision rule is delegated to the gateway, not reimplemented`() = runTest {
        val gw = gateway()
        val original = item("fake://primary/a.gif", "i1")
        gw.register(original)
        gw.createFileAt("fake://export/a.gif", mime = "image/gif")

        SlotMoveCoordinator(gw).apply(moveSlot(), original)

        assertEquals(
            1,
            gw.faultInjector.operationCount(),
            "the coordinator must route the move through StorageGateway.move (one counted MOVE), not touch files itself",
        )
    }

    @Test
    fun `a slot with no destination tags the item and moves nothing`() = runTest {
        val gw = gateway()
        val original = item("fake://primary/a.gif", "i1")
        gw.register(original, "GIF89a".toByteArray())
        val tags = RecordingTagSink()
        val tagSlot = moveSlot().copy(destinationUri = null, mode = SlotMode.TAG)

        val result = SlotMoveCoordinator(gw, tags).apply(tagSlot, original)

        assertTrue(result is SlotMoveOutcome.Tagged)
        assertEquals(0, gw.faultInjector.operationCount(), "TAG must not touch the gateway at all")
        assertEquals(listOf(original.id), tags.tagged.map { it.itemId })
        assertEquals(tagSlot.id, tags.tagged.single().slotId)
    }

    @Test
    fun `a slot whose destination grant is gone reports unavailable and moves nothing`() = runTest {
        val gw = gateway()
        val original = item("fake://primary/a.gif", "i1")
        gw.register(original)

        val result = SlotMoveCoordinator(gw).apply(moveSlot().copy(destinationUri = "fake://revoked"), original)

        assertTrue(result is SlotMoveOutcome.DestinationUnavailable)
        assertTrue(gw.fileExists("fake://primary/a.gif"))
        assertEquals(0, gw.faultInjector.operationCount())
    }

    @Test
    fun `a move waits for the recovery gate before touching the gateway`() = runTest {
        val gw = gateway()
        val original = item("fake://primary/a.gif", "i1")
        gw.register(original)
        val ready = CompletableDeferred<Unit>()
        val coordinator = SlotMoveCoordinator(gw, gate = MutationGate { ready.await() })

        val job = launch { coordinator.apply(moveSlot(), original) }
        advanceUntilIdle()

        assertEquals(0, gw.faultInjector.operationCount(), "D6: no move may run before recovery readiness")
        assertTrue(gw.fileExists("fake://primary/a.gif"))

        ready.complete(Unit)
        job.join()

        assertEquals(1, gw.faultInjector.operationCount(), "once ready, the move runs exactly once")
        assertFalse(gw.fileExists("fake://primary/a.gif"))
    }

    private class RecordingTagSink : SlotTagSink {
        val tagged = mutableListOf<Tag>()
        override suspend fun tag(tag: Tag) {
            tagged += tag
        }
    }
}
