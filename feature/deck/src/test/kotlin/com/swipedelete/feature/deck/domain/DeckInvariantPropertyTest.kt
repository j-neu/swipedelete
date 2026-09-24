package com.swipedelete.feature.deck.domain

import com.swipedelete.core.model.ItemId
import com.swipedelete.core.model.SlotId
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Seeded property tests over random gesture sequences (D3: kotlin.test +
 * kotlin.random.Random, no new dependency). Every loop runs
 * [sequenceCount] seeds x [gesturesPerSequence] gestures.
 */
class DeckInvariantPropertyTest {

    private val sequenceCount = 2000
    private val gesturesPerSequence = 200

    private fun initialState(count: Int = 30) =
        DeckState(items = (1..count).map { ItemId("i$it") })

    private fun step(state: DeckState, rnd: Random): DeckState {
        val gesture = Gesture.entries.random(rnd)
        val slot = if (gesture == Gesture.MOVE) SlotId(rnd.nextInt(5)) else null
        return DeckReducer.reduce(state, gesture, slot)
    }

    private fun assertCoreInvariants(state: DeckState, seed: Int, step: Int) {
        val where = "seed=$seed step=$step"
        val queue = state.items.toSet()
        assertTrue(state.staged.intersect(state.kept.toSet()).isEmpty(), "staged and kept overlap $where")
        assertTrue(state.staged.intersect(state.decided.toSet()).isEmpty(), "staged and decided overlap $where")
        assertTrue(state.kept.intersect(state.decided.toSet()).isEmpty(), "kept and decided overlap $where")
        assertTrue(state.staged.none { it in queue }, "a staged item is still in the queue $where")
        assertTrue(state.kept.none { it in queue }, "a kept item is still in the queue $where")
        assertTrue(state.decided.none { it in queue }, "a decided item is still in the queue $where")
        assertEquals(state.items.size, state.items.distinct().size, "queue lists an item twice $where")
        assertTrue(state.items.isEmpty() || state.cursor in state.items.indices, "cursor out of range $where")
        assertTrue(
            state.deferred.all { it.count in 1..DeckReducer.MAX_REQUEUES },
            "a defer count escaped 1..${DeckReducer.MAX_REQUEUES} $where",
        )
        state.history.filterIsInstance<DeckAction.Defer>().filter { it.requeued }
            .groupBy { it.item }
            .forEach { (item, defers) ->
                assertTrue(
                    defers.size <= DeckReducer.MAX_REQUEUES,
                    "$item re-queued ${defers.size} times $where",
                )
            }
    }

    @Test
    fun `an item is never both staged and kept`() {
        repeat(sequenceCount) { seed ->
            var state = initialState()
            val rnd = Random(seed)
            repeat(gesturesPerSequence) { step ->
                state = step(state, rnd)
                assertTrue(
                    state.staged.intersect(state.kept.toSet()).isEmpty(),
                    "an item is both staged and kept: seed=$seed step=$step",
                )
                assertCoreInvariants(state, seed, step)
            }
        }
    }

    @Test
    fun `history is invertible`() {
        repeat(sequenceCount) { seed ->
            val initial = initialState()
            var state = initial
            val rnd = Random(seed)
            repeat(gesturesPerSequence) { state = step(state, rnd) }
            while (state.history.isNotEmpty()) {
                state = DeckReducer.undo(state)
            }
            assertEquals(initial, state, "undoing every action must restore the initial deck: seed=$seed")
        }
    }

    @Test
    fun `a deferred item re-queues at most three times then lands in decide later`() {
        repeat(sequenceCount) { seed ->
            var state = initialState()
            val rnd = Random(seed)
            repeat(gesturesPerSequence) { step ->
                state = step(state, rnd)
                assertTrue(
                    state.deferred.all { it.count <= DeckReducer.MAX_REQUEUES },
                    "a deferred item re-queued more than ${DeckReducer.MAX_REQUEUES} times: seed=$seed step=$step",
                )
                assertCoreInvariants(state, seed, step)
            }
        }

        val only = ItemId("only")
        var solo = DeckState(items = listOf(only))
        repeat(DeckReducer.MAX_REQUEUES) { solo = DeckReducer.reduce(solo, Gesture.DEFER) }
        assertEquals(listOf(only), solo.items, "the item must still be queued while it has re-queues left")
        assertEquals(DeckReducer.MAX_REQUEUES, solo.deferred.single().count)
        assertTrue(solo.decided.isEmpty(), "the item must not be decided before its budget is spent")

        solo = DeckReducer.reduce(solo, Gesture.DEFER)
        assertTrue(solo.items.isEmpty(), "the item must leave the queue once its re-queue budget is spent")
        assertEquals(listOf(only), solo.decided, "the item must land in the decide-later pile")
        assertEquals(DeckReducer.MAX_REQUEUES, solo.deferred.single().count, "the cap must not be exceeded")

        repeat(5) { solo = DeckReducer.reduce(solo, Gesture.DEFER) }
        assertTrue(solo.items.isEmpty(), "a decide-later item must never re-enter the queue")
    }

    @Test
    fun `no item repeats within a session`() {
        repeat(sequenceCount) { seed ->
            var state = initialState()
            val rnd = Random(seed)
            repeat(gesturesPerSequence) { step ->
                state = step(state, rnd)
                val decided = (state.staged + state.kept + state.decided).toSet()
                assertTrue(
                    decided.none { it in state.items },
                    "a decided item re-entered the deck: seed=$seed step=$step",
                )
                assertEquals(
                    state.items.size,
                    state.items.distinct().size,
                    "an item appears twice in the queue: seed=$seed step=$step",
                )
                assertCoreInvariants(state, seed, step)
            }
        }
    }
}
