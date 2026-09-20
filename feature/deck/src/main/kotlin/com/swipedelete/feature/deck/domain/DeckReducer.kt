package com.swipedelete.feature.deck.domain

import com.swipedelete.core.model.ItemId
import com.swipedelete.core.model.SlotId

/**
 * Pure transition function for the triage deck (§9.2). Every returned state is
 * a new value; the input is never mutated.
 */
object DeckReducer {

    /** How many times a deferred item may re-queue before it lands in "decide later" (§5.1). */
    const val MAX_REQUEUES: Int = 3

    fun reduce(state: DeckState, gesture: Gesture, slot: SlotId? = null): DeckState =
        when (gesture) {
            Gesture.DELETE -> delete(state)
            Gesture.KEEP -> keep(state)
            Gesture.DEFER -> defer(state)
            Gesture.MOVE -> move(state, slot)
            Gesture.UNDO -> undo(state)
        }

    /** Reverts the most recent action and drops it from [DeckState.history]. */
    fun undo(state: DeckState): DeckState {
        val action = state.history.lastOrNull() ?: return state
        val history = state.history.dropLast(1)
        return when (action) {
            is DeckAction.Delete -> state.copy(
                items = state.items.insert(action.item, action.at),
                cursor = action.at,
                staged = state.staged - action.item,
                history = history,
            )

            is DeckAction.Keep -> state.copy(
                items = state.items.insert(action.item, action.at),
                cursor = action.at,
                kept = state.kept - action.item,
                history = history,
            )

            is DeckAction.Move -> state.copy(
                items = state.items.insert(action.item, action.at),
                cursor = action.at,
                decided = state.decided - action.item,
                history = history,
            )

            is DeckAction.Defer -> undoDefer(state, action, history)
            is DeckAction.Undo -> state.copy(history = history)
        }
    }

    private fun delete(state: DeckState): DeckState {
        val item = state.currentItem() ?: return state
        val at = state.cursor
        val items = state.items.without(at)
        return state.copy(
            items = items,
            cursor = items.nextCursor(at),
            staged = state.staged + item,
            history = state.history + DeckAction.Delete(item, at),
        )
    }

    private fun keep(state: DeckState): DeckState {
        val item = state.currentItem() ?: return state
        val at = state.cursor
        val items = state.items.without(at)
        return state.copy(
            items = items,
            cursor = items.nextCursor(at),
            kept = state.kept + item,
            history = state.history + DeckAction.Keep(item, at),
        )
    }

    private fun move(state: DeckState, slot: SlotId?): DeckState {
        if (slot == null) return state
        val item = state.currentItem() ?: return state
        val at = state.cursor
        val items = state.items.without(at)
        return state.copy(
            items = items,
            cursor = items.nextCursor(at),
            decided = state.decided + item,
            history = state.history + DeckAction.Move(item, slot, at),
        )
    }

    private fun defer(state: DeckState): DeckState {
        val item = state.currentItem() ?: return state
        val at = state.cursor
        val previous = state.deferCount(item)
        val requeued = previous < MAX_REQUEUES
        val count = if (requeued) previous + 1 else previous
        val items = state.items.without(at).let { if (requeued) it + item else it }
        return state.copy(
            items = items,
            cursor = items.nextCursor(at),
            deferred = state.deferred.withCount(item, count),
            decided = if (requeued) state.decided else state.decided + item,
            history = state.history + DeckAction.Defer(item, at, count, requeued),
        )
    }

    private fun undoDefer(
        state: DeckState,
        action: DeckAction.Defer,
        history: List<DeckAction>,
    ): DeckState =
        if (action.requeued) {
            val items = state.items
                .without(state.items.lastIndexOf(action.item))
                .insert(action.item, action.at)
            state.copy(
                items = items,
                cursor = action.at,
                deferred = state.deferred.withCount(action.item, action.count - 1),
                history = history,
            )
        } else {
            state.copy(
                items = state.items.insert(action.item, action.at),
                cursor = action.at,
                decided = state.decided - action.item,
                history = history,
            )
        }
}

private fun DeckState.currentItem(): ItemId? = items.getOrNull(cursor)

private fun DeckState.deferCount(item: ItemId): Int =
    deferred.firstOrNull { it.item == item }?.count ?: 0

private fun List<ItemId>.without(index: Int): List<ItemId> =
    toMutableList().apply { removeAt(index) }

private fun List<ItemId>.insert(item: ItemId, index: Int): List<ItemId> =
    toMutableList().apply { add(index.coerceIn(0, size), item) }

private fun List<ItemId>.nextCursor(at: Int): Int =
    if (isEmpty()) 0 else at.coerceIn(0, lastIndex)

private fun List<Deferred>.withCount(item: ItemId, count: Int): List<Deferred> {
    if (count <= 0) return filterNot { it.item == item }
    val index = indexOfFirst { it.item == item }
    if (index < 0) return this + Deferred(item, count)
    return toMutableList().apply { this[index] = Deferred(item, count) }
}
