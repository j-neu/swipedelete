package com.swipedelete.feature.deck.session

import com.swipedelete.core.model.BatchId
import com.swipedelete.core.model.ItemId
import com.swipedelete.core.model.MediaItem
import com.swipedelete.core.model.SessionId
import com.swipedelete.core.model.Slot
import com.swipedelete.core.model.SlotId
import com.swipedelete.core.model.SlotMode
import com.swipedelete.core.model.SourceId
import com.swipedelete.core.model.SourceKind
import com.swipedelete.core.model.SourceRef
import com.swipedelete.core.model.Tag
import com.swipedelete.core.storage.Destination
import com.swipedelete.core.storage.IndividuallyReviewed
import com.swipedelete.core.storage.MoveResult
import com.swipedelete.core.storage.StorageGateway
import com.swipedelete.core.storage.fake.FakeStorageGateway
import com.swipedelete.feature.deck.domain.DeckBuilder
import com.swipedelete.feature.deck.domain.DeckQuery
import com.swipedelete.feature.deck.domain.FakeScanPort
import com.swipedelete.feature.deck.domain.FixedClock
import com.swipedelete.feature.deck.domain.Gesture
import com.swipedelete.feature.deck.domain.SlotMoveOutcome
import com.swipedelete.feature.deck.domain.SlotTagSink
import com.swipedelete.feature.deck.domain.page
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest

/**
 * SPEC 5.3 / AC-8: what the session does when a card is dropped on a slot, and
 * that the deck never claims a tag, a move or an undo that did not happen.
 */
class SessionSlotMoveTest {

    private val source = ref("src-primary", "fake://primary")
    private val export = ref("src-export", "fake://export")
    private val fake = FakeStorageGateway().apply {
        addSource(source)
        addSource(export)
    }

    /** The fake gateway, with a switch that makes every move fail. */
    private var failMoves = false
    private val reviewedDuringMoves = mutableListOf<Set<ItemId>?>()
    private val gateway: StorageGateway = object : StorageGateway by fake {
        override suspend fun move(item: MediaItem, destination: Destination): MoveResult {
            reviewedDuringMoves += currentCoroutineContext()[IndividuallyReviewed]?.items
            return moveOrFail(item, destination)
        }

        private suspend fun moveOrFail(item: MediaItem, destination: Destination): MoveResult =
            if (failMoves) MoveResult.Failed("destination is read-only") else fake.move(item, destination)
    }

    private val items = listOf(item("fake://primary/a.gif", "i1"), item("fake://primary/b.gif", "i2"))
    private val tags = RecordingTagSink()

    init {
        items.forEach { fake.register(it) }
    }

    private val tagSlot = Slot(SlotId(2), "Funny", "tag", 0L, destinationUri = null, mode = SlotMode.TAG, sortOrder = 1)
    private val moveSlot =
        Slot(SlotId(1), "Memes", "folder", 0L, destinationUri = export.treeUri, mode = SlotMode.MOVE, sortOrder = 0)

    private suspend fun controller(mutations: SessionMutations = RecordingMutations()): SessionController {
        val controller = SessionController(
            sessions = FakeDeckSessionDao(),
            builder = DeckBuilder({ FakeScanPort(page(items)) }, gateway, FixedClock(0L)),
            clock = FixedClock(0L),
            gate = MutationGate { },
            mutations = mutations,
            measuredFreed = { 0L },
            sessionIds = { SessionId("session-move") },
            batches = { BatchId("batch-1") },
            gateway = gateway,
            tags = tags,
        )
        controller.start(DeckQuery.Folder(source.id))
        return controller
    }

    private fun SessionController.currentId(): ItemId? =
        currentState().let { it.items.getOrNull(it.cursor) }

    @Test
    fun `dropping a card on a tag-only slot writes the tag and advances, leaving the file alone`() = runTest {
        val controller = controller()

        val outcome = controller.applySlotMove(tagSlot, items[0])

        assertIs<SlotMoveOutcome.Tagged>(outcome)
        assertEquals(listOf(Tag(ItemId("i1"), SlotId(2), 0L)), tags.tagged)
        assertEquals(ItemId("i2"), controller.currentId(), "a tagged card is decided, so the deck moves on")
        assertTrue(fake.fileExists("fake://primary/a.gif"), "a tag never touches the file")
    }

    @Test
    fun `undoing a tag removes it and brings the card back`() = runTest {
        val controller = controller()
        controller.applySlotMove(tagSlot, items[0])

        controller.undoLast()

        assertEquals(listOf(ItemId("i1") to SlotId(2)), tags.untagged)
        assertEquals(ItemId("i1"), controller.currentId())
    }

    @Test
    fun `a slot move and its undo are marked as reviewed one item at a time`() = runTest {
        val controller = controller()
        controller.applySlotMove(moveSlot, items[0])
        controller.undoLast()

        assertEquals(2, reviewedDuringMoves.size)
        assertEquals(setOf(ItemId("i1")), reviewedDuringMoves[0])
        assertEquals(1, reviewedDuringMoves[1]?.size, "the move back carries the moved item, and only it")
    }

    @Test
    fun `a card that changed on disk is not moved`() = runTest {
        val changed = object : SessionMutations by RecordingMutations() {
            override suspend fun staleReason(item: MediaItem): String? = "size changed since the deck was built"
        }
        val controller = controller(mutations = changed)

        val outcome = controller.applySlotMove(moveSlot, items[0])

        assertEquals(SlotMoveOutcome.Stale("size changed since the deck was built"), outcome)
        assertTrue(fake.fileExists("fake://primary/a.gif"), "AC-8: a changed item is left where it is")
        assertTrue(controller.currentState().history.isEmpty())
    }

    @Test
    fun `an undo whose move back fails is reported and the move stays recorded`() = runTest {
        val controller = controller()
        controller.applySlotMove(moveSlot, items[0])
        failMoves = true

        assertFailsWith<UndoFailedException> { controller.undoLast() }

        assertEquals(1, controller.currentState().history.size, "the file is still moved, so the deck must say so")
        assertTrue(fake.fileExists("fake://export/a.gif"))
    }

    @Test
    fun `a delete that was already applied cannot be undone from the history strip`() = runTest {
        val controller = controller()
        controller.decide(Gesture.DELETE)
        controller.applyStaged()

        assertFailsWith<UndoFailedException> { controller.revert(0) }

        assertEquals(listOf(ItemId("i1")), controller.currentState().decided, "it is in quarantine; only a restore brings it back")
    }

    private class RecordingTagSink : SlotTagSink {
        val tagged = mutableListOf<Tag>()
        val untagged = mutableListOf<Pair<ItemId, SlotId>>()

        override suspend fun tag(tag: Tag) {
            tagged += tag
        }

        override suspend fun untag(itemId: ItemId, slotId: SlotId) {
            untagged += itemId to slotId
        }
    }

    private fun ref(id: String, treeUri: String) = SourceRef(
        id = SourceId(id),
        treeUri = treeUri,
        displayName = id,
        access = SourceRef.Access.RW,
        kind = SourceKind.RAW,
        itemCount = null,
        totalBytes = null,
        grantedAt = 0L,
    )

    private fun item(path: String, id: String) = MediaItem(
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
}
