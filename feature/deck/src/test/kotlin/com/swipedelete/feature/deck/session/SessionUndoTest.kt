package com.swipedelete.feature.deck.session

import com.swipedelete.core.model.ItemId
import com.swipedelete.core.model.SlotId
import com.swipedelete.feature.deck.domain.DeckAction
import com.swipedelete.feature.deck.domain.DeckReducer
import com.swipedelete.feature.deck.domain.DeckState
import com.swipedelete.feature.deck.domain.Gesture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * SPEC 5.5 / M1-T07: the undo stack is session-wide, not last-action-only. Any
 * entry in the history strip can be reverted on its own, and a two-finger tap
 * reverts only the newest entry. Reverting a staged delete or a keep is pure
 * state; reverting a move is a file operation, so it goes through the gateway.
 */
class SessionUndoTest {

    private val moveReverts = mutableListOf<DeckAction.Move>()
    private val stack = UndoStack { moveReverts += it }

    private fun deck(count: Int = 3): DeckState =
        DeckState(items = (1..count).map { ItemId("i$it") })

    private fun DeckState.decide(gesture: Gesture, slot: SlotId? = null): DeckState =
        DeckReducer.reduce(this, gesture, slot)

    /** DELETE i1, KEEP i2, DELETE i3 — a three-entry session with a revertible middle. */
    private fun threeEntrySession(): DeckState =
        deck().decide(Gesture.DELETE).decide(Gesture.KEEP).decide(Gesture.DELETE)

    @Test
    fun `reverting a middle keep restores it and leaves the other entries in force`() = runTest {
        val state = threeEntrySession()
        assertEquals(3, state.history.size, "precondition: the session recorded three actions")

        val reverted = stack.revert(state, index = 1)

        assertEquals(2, reverted.history.size, "only the reverted entry leaves the history")
        assertTrue(
            reverted.history.none { it is DeckAction.Keep },
            "the reverted keep must leave the strip",
        )
        assertTrue(
            reverted.history.any { it is DeckAction.Delete && it.item == ItemId("i1") },
            "the entry before the reverted one must survive",
        )
        assertTrue(
            reverted.history.any { it is DeckAction.Delete && it.item == ItemId("i3") },
            "the entry after the reverted one must survive",
        )

        assertTrue(reverted.kept.isEmpty(), "reverting a keep must clear it from kept")
        assertEquals(listOf(ItemId("i2")), reverted.items, "the kept item returns to the deck")
        assertEquals(
            listOf(ItemId("i1"), ItemId("i3")),
            reverted.staged,
            "the surrounding staged deletes stay staged",
        )
        assertTrue(moveReverts.isEmpty(), "a keep revert is state-only, never a gateway call")
    }

    @Test
    fun `reverting a middle staged delete restores the item but keeps later entries`() = runTest {
        val state = threeEntrySession()

        val reverted = stack.revert(state, index = 0)

        assertEquals(2, reverted.history.size)
        assertTrue(reverted.history.any { it is DeckAction.Keep }, "the later keep must survive")
        assertTrue(
            reverted.history.any { it is DeckAction.Delete && it.item == ItemId("i3") },
            "the later staged delete must survive",
        )
        assertEquals(listOf(ItemId("i1")), reverted.items, "the staged item returns to the deck")
        assertEquals(listOf(ItemId("i3")), reverted.staged, "only the reverted delete is unstaged")
        assertTrue(moveReverts.isEmpty(), "a staged revert is state-only, never a gateway call")
    }

    @Test
    fun `reverting a middle defer restores its queue position and count`() = runTest {
        // DEFER i1 (requeued to the back), then DELETE the newly-current i2.
        val state = deck().decide(Gesture.DEFER).decide(Gesture.DELETE)
        assertEquals(2, state.history.size)

        val reverted = stack.revert(state, index = 0)

        assertEquals(1, reverted.history.size)
        assertTrue(reverted.history.single() is DeckAction.Delete, "the later delete must survive")
        assertEquals(listOf(ItemId("i1"), ItemId("i3")), reverted.items)
        assertTrue(reverted.deferred.isEmpty(), "the defer count must be rolled back")
    }

    @Test
    fun `reverting a move undoes the file operation through the gateway`() = runTest {
        val slot = SlotId(2)
        val state = deck().decide(Gesture.MOVE, slot)

        val reverted = stack.revert(state, index = 0)

        assertEquals(
            listOf(DeckAction.Move(ItemId("i1"), slot, 0)),
            moveReverts,
            "a move revert is a gateway call",
        )
        assertTrue(reverted.decided.isEmpty(), "the moved item leaves the decided pile")
        assertEquals(
            listOf(ItemId("i1"), ItemId("i2"), ItemId("i3")),
            reverted.items,
            "the moved item returns to its place in the deck",
        )
        assertTrue(reverted.history.isEmpty())
    }

    @Test
    fun `a two-finger tap undoes only the last action`() = runTest {
        // SPEC 5.2: two-finger tap = undo last.
        val state = deck().decide(Gesture.DELETE).decide(Gesture.KEEP)

        val after = stack.undoLast(state)

        assertEquals(
            DeckReducer.undo(state),
            after,
            "the two-finger tap must match the engine's undo-last semantics",
        )
        assertEquals(1, after.history.size, "only the last entry is undone")
        assertTrue(after.kept.isEmpty())
        assertTrue(moveReverts.isEmpty())
    }

    @Test
    fun `a two-finger tap on an empty session is a no-op`() = runTest {
        val state = deck()

        assertEquals(state, stack.undoLast(state))
    }

    @Test
    fun `reverting an out-of-range entry is a no-op`() = runTest {
        val state = deck().decide(Gesture.DELETE)

        assertEquals(state, stack.revert(state, index = 5))
        assertEquals(state, stack.revert(state, index = -1))
    }
}
