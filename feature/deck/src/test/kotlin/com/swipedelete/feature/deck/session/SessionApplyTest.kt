package com.swipedelete.feature.deck.session

import com.swipedelete.core.model.BatchId
import com.swipedelete.core.model.ItemId
import com.swipedelete.core.model.MediaItem
import com.swipedelete.core.model.SessionId
import com.swipedelete.core.storage.Failure
import com.swipedelete.core.storage.fake.FakeStorageGateway
import com.swipedelete.feature.deck.domain.DeckBuilder
import com.swipedelete.feature.deck.domain.DeckQuery
import com.swipedelete.feature.deck.domain.FakeScanPort
import com.swipedelete.feature.deck.domain.FixedClock
import com.swipedelete.feature.deck.domain.Gesture
import com.swipedelete.feature.deck.domain.mediaItem
import com.swipedelete.feature.deck.domain.page
import com.swipedelete.feature.deck.domain.sourceRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * SPEC 5.2 / decision 3 (staged-by-default), SPEC 6.6 / D6 (Apply is one gated
 * commit) and AC-8 (a changed item is never destroyed).
 *
 * There is exactly one Apply path — the session-owned
 * [SessionController.applyStaged]. These tests drive it directly: a swipe only
 * stages, and only Apply (after recovery readiness, with each item re-checked
 * against the gateway) performs the destructive call.
 */
class SessionApplyTest {

    private val gateway = FakeStorageGateway()
    private val sessions = FakeDeckSessionDao()
    private val mutations = RecordingMutations()
    private val source = sourceRef("src")

    private val items = listOf(
        mediaItem("i1", "content://tree/src/i1.jpg", sourceId = source.id),
        mediaItem("i2", "content://tree/src/i2.jpg", sourceId = source.id),
    )

    init {
        gateway.addSource(source)
    }

    private fun newController(
        gate: MutationGate = MutationGate { },
        mutations: SessionMutations = this.mutations,
    ) = SessionController(
        sessions = sessions,
        builder = DeckBuilder({ FakeScanPort(page(items)) }, gateway, FixedClock(0L)),
        clock = FixedClock(0L),
        gate = gate,
        mutations = mutations,
        measuredFreed = { 0L },
        reclaimable = { 0L },
        sessionIds = { SESSION },
        batches = { BatchId("batch-1") },
        gateway = gateway,
    )

    @Test
    fun `a staged delete touches nothing until Apply`() = runTest {
        val controller = newController()
        controller.start(DeckQuery.Folder(source.id))

        controller.decide(Gesture.DELETE)

        assertTrue(mutations.quarantined.isEmpty(), "a swipe must stage, never quarantine")
        assertEquals(listOf(ItemId("i1")), controller.currentState().staged)
    }

    @Test
    fun `Apply awaits readiness then quarantines the whole staged set once`() = runTest {
        val order = mutableListOf<String>()
        val ordering = object : SessionMutations {
            override suspend fun quarantine(items: List<MediaItem>, batch: BatchId): QuarantineOutcome {
                order += "quarantine"
                return allMoved(items)
            }

            override suspend fun restoreSession(session: SessionId): Int = 0
        }
        val controller = newController(gate = MutationGate { order += "awaitReady" }, mutations = ordering)
        controller.start(DeckQuery.Folder(source.id))
        controller.decide(Gesture.DELETE)
        controller.decide(Gesture.DELETE)

        val result = controller.applyStaged()

        assertEquals(2, result.quarantined)
        assertTrue(result.aborted.isEmpty())
        assertEquals(
            listOf("awaitReady", "quarantine"),
            order,
            "D6: recovery readiness must be awaited before the destructive call",
        )
        assertTrue(controller.currentState().staged.isEmpty(), "applied items leave the strip")
    }

    @Test
    fun `Apply aborts a staged item that changed on disk and keeps it staged`() = runTest {
        val changed = object : SessionMutations {
            override suspend fun quarantine(items: List<MediaItem>, batch: BatchId): QuarantineOutcome = allMoved(items)

            override suspend fun restoreSession(session: SessionId): Int = 0

            override suspend fun staleReason(item: MediaItem): String? =
                if (item.id == ItemId("i1")) "size changed since the deck was built" else null
        }
        val controller = newController(mutations = changed)
        controller.start(DeckQuery.Folder(source.id))
        controller.decide(Gesture.DELETE)
        controller.decide(Gesture.DELETE)

        val result = controller.applyStaged()

        assertEquals(1, result.quarantined, "only the unchanged item may be destroyed")
        assertEquals(listOf(ItemId("i1")), result.aborted.map { it.item.id })
        assertEquals("size changed since the deck was built", result.aborted.single().reason)
        assertEquals(
            listOf(ItemId("i1")),
            controller.currentState().staged,
            "AC-8: an aborted item stays staged so it is never silently destroyed",
        )
        assertEquals(listOf(ItemId("i2")), controller.currentState().decided)
    }


    @Test
    fun `Apply keeps an item the gateway refused staged and reports why`() = runTest {
        val refusing = object : SessionMutations {
            override suspend fun quarantine(items: List<MediaItem>, batch: BatchId) = QuarantineOutcome(
                moved = setOf(ItemId("i2")),
                failures = listOf(Failure(ItemId("i1"), "protected_root")),
            )

            override suspend fun restoreSession(session: SessionId): Int = 0
        }
        val controller = newController(mutations = refusing)
        controller.start(DeckQuery.Folder(source.id))
        controller.decide(Gesture.DELETE)
        controller.decide(Gesture.DELETE)

        val result = controller.applyStaged()

        assertEquals(1, result.quarantined, "only what the gateway moved counts as quarantined")
        assertEquals(listOf(ItemId("i1")), result.failed.map { it.item.id })
        assertEquals("protected_root", result.failed.single().reason)
        assertEquals(
            listOf(ItemId("i1")),
            controller.currentState().staged,
            "a refused item is still on disk, so it must stay staged, not look deleted",
        )
        assertEquals(listOf(ItemId("i2")), controller.currentState().decided)
    }

    @Test
    fun `Apply keeps an item with no reported outcome staged`() = runTest {
        val silent = object : SessionMutations {
            override suspend fun quarantine(items: List<MediaItem>, batch: BatchId) =
                QuarantineOutcome(moved = emptySet(), failures = emptyList())

            override suspend fun restoreSession(session: SessionId): Int = 0
        }
        val controller = newController(mutations = silent)
        controller.start(DeckQuery.Folder(source.id))
        controller.decide(Gesture.DELETE)

        val result = controller.applyStaged()

        assertEquals(0, result.quarantined)
        assertEquals(listOf(ItemId("i1")), result.failed.map { it.item.id })
        assertEquals(listOf(ItemId("i1")), controller.currentState().staged)
        assertTrue(controller.currentState().decided.isEmpty())
    }

    private companion object {
        val SESSION = SessionId("session-apply")
    }
}
