package com.swipedelete.feature.deck.session

import com.swipedelete.core.model.DeckStatus
import com.swipedelete.core.model.ItemId
import com.swipedelete.core.model.SessionId
import com.swipedelete.core.model.SlotId
import com.swipedelete.core.storage.fake.FakeStorageGateway
import com.swipedelete.feature.deck.domain.DeckBuilder
import com.swipedelete.feature.deck.domain.DeckQuery
import com.swipedelete.feature.deck.domain.DeckState
import com.swipedelete.feature.deck.domain.FixedClock
import com.swipedelete.feature.deck.domain.FakeScanPort
import com.swipedelete.feature.deck.domain.Gesture
import com.swipedelete.feature.deck.domain.mediaItem
import com.swipedelete.feature.deck.domain.page
import com.swipedelete.feature.deck.domain.sourceRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * SPEC 5.5 / M1-T12: a session is persisted per decision, and a crash or exit
 * mid-triage resumes at exactly the same place. The durable store is a
 * [FakeDeckSessionDao]; "process death" is simulated by dropping the controller
 * and constructing a new one over the same store, exactly as the next launch
 * would.
 */
class SessionResumeTest {

    private val gateway = FakeStorageGateway()
    private val sessions = FakeDeckSessionDao()
    private val mutations = RecordingMutations()
    private val source = sourceRef("src")

    private val items = listOf(
        mediaItem("i1", "content://tree/src/i1.jpg", sourceId = source.id),
        mediaItem("i2", "content://tree/src/i2.jpg", sourceId = source.id),
        mediaItem("i3", "content://tree/src/i3.jpg", sourceId = source.id),
        mediaItem("i4", "content://tree/src/i4.jpg", sourceId = source.id),
        mediaItem("i5", "content://tree/src/i5.jpg", sourceId = source.id),
    )

    private val query = DeckQuery.Folder(source.id)

    init {
        gateway.addSource(source)
    }

    private fun newController(): SessionController = SessionController(
        sessions = sessions,
        builder = DeckBuilder({ FakeScanPort(page(items)) }, gateway, FixedClock(0L)),
        clock = FixedClock(0L),
        gate = MutationGate { },
        mutations = mutations,
        measuredFreed = { 0L },
        reclaimable = { 0L },
        sessionIds = { SESSION },
        batches = { com.swipedelete.core.model.BatchId("batch-1") },
    )

    @Test
    fun `starting a session persists an ACTIVE row at the start of the deck`() = runTest {
        val controller = newController()

        val state = controller.start(query)

        assertEquals(items.map { it.id }, state.items)
        assertEquals(0, state.cursor)
        val row = assertNotNull(sessions.byId(SESSION.value))
        assertEquals(DeckStatus.ACTIVE, row.status)
        assertEquals(0, row.cursor)
        assertEquals(query, DeckQueryCodec.decode(row.queryJson))
    }

    @Test
    fun `every decision persists the cursor and the exact session state`() = runTest {
        val controller = newController()
        controller.start(query)

        controller.decide(Gesture.DELETE)
        var row = assertNotNull(sessions.byId(SESSION.value))
        assertEquals(controller.currentState().cursor, row.cursor)
        assertEquals(controller.currentState(), DeckStateCodec.decode(row.stateData))

        controller.decide(Gesture.KEEP)
        controller.decide(Gesture.MOVE, SlotId(2))
        row = assertNotNull(sessions.byId(SESSION.value))
        assertEquals(controller.currentState().cursor, row.cursor)
        assertEquals(controller.currentState(), DeckStateCodec.decode(row.stateData))
        assertEquals(
            listOf(ItemId("i1"), ItemId("i2")),
            controller.currentState().staged + controller.currentState().kept,
            "the persisted state must carry every decision, not just the cursor",
        )
    }

    @Test
    fun `process death mid-triage resumes the exact state and cursor`() = runTest {
        val first = newController()
        first.start(query)
        first.decide(Gesture.DELETE)
        first.decide(Gesture.KEEP)
        first.decide(Gesture.DEFER)
        val before = first.currentState()
        assertEquals(3, before.history.size, "precondition: three decisions recorded")

        // Simulated process death: the controller is gone, the durable row is not.
        val second = newController()
        val resumed = second.resume(SESSION)

        assertEquals(before, resumed, "resume must restore the exact session state")
        assertEquals(before.cursor, resumed.cursor, "the exact cursor must be restored")
        assertEquals(
            before.items.getOrNull(before.cursor),
            resumed.items.getOrNull(resumed.cursor),
            "the same card must be in front of the user again",
        )
    }

    @Test
    fun `triaging continues after a resume and keeps persisting`() = runTest {
        val first = newController()
        first.start(query)
        first.decide(Gesture.DELETE)

        val second = newController()
        second.resume(SESSION)
        second.decide(Gesture.KEEP)

        val row = assertNotNull(sessions.byId(SESSION.value))
        assertEquals(2, second.currentState().history.size)
        assertEquals(second.currentState(), DeckStateCodec.decode(row.stateData))
    }

    @Test
    fun `resuming an unknown session is refused loudly`() = runTest {
        val controller = newController()

        assertFailsWith<IllegalArgumentException> { controller.resume(SessionId("nope")) }
    }

    @Test
    fun `a resume query is handled by resume, not start`() = runTest {
        val controller = newController()

        assertFailsWith<IllegalArgumentException> {
            controller.start(DeckQuery.Resume(SessionId("other")))
        }
    }

    @Test
    fun `discarding staging returns the staged items to the deck without deleting`() = runTest {
        val controller = newController()
        controller.start(query)
        controller.decide(Gesture.DELETE)

        val after = controller.discardStaging()

        assertTrue(after.staged.isEmpty(), "discarding clears staging")
        assertEquals(items.map { it.id }, after.items, "the staged item is back in the deck")
        assertTrue(mutations.quarantined.isEmpty(), "discarding is not a destructive action")
    }

    @Test
    fun `apply is the destructive path and empties staging through the gate`() = runTest {
        val controller = newController()
        controller.start(query)
        controller.decide(Gesture.DELETE)
        controller.decide(Gesture.DELETE)

        val result = controller.applyStaged()

        assertEquals(2, result.quarantined)
        assertTrue(result.aborted.isEmpty())
        assertEquals(listOf(ItemId("i1"), ItemId("i2")), mutations.quarantined.single().map { it.id })
        assertTrue(controller.currentState().staged.isEmpty(), "applied items leave the staging strip")
        assertEquals(
            listOf(ItemId("i1"), ItemId("i2")),
            controller.currentState().decided,
            "applied items become decided",
        )
    }

    private companion object {
        val SESSION = SessionId("session-1")
    }
}
