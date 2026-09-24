package com.swipedelete.feature.deck.session

import com.swipedelete.core.model.ItemId
import com.swipedelete.feature.deck.domain.DeckAction
import com.swipedelete.feature.deck.domain.DeckReducer
import com.swipedelete.feature.deck.domain.DeckState
import com.swipedelete.feature.deck.domain.Deferred

/**
 * Session-wide undo over the deck action log (SPEC 5.5 / M1-T07).
 *
 * [DeckReducer.undo] can only invert the newest action; the history strip lets
 * the user revert *any* entry, so this stack selects by index instead of by
 * "last". It deliberately holds no state of its own — the log already lives in
 * [DeckState.history], and a second copy here could drift out of sync with it.
 *
 * Reverting a staged delete, a keep or a defer is pure state surgery. Reverting
 * a move must undo the file operation too, so it is handed to [undoMove]: the
 * session layer supplies a `StorageGateway`-backed operation, and this class
 * imports no `android.*` type (D5).
 */
class UndoStack(
    private val undoMove: suspend (DeckAction.Move) -> Unit = {},
) {

    /** Two-finger tap (SPEC 5.2): revert the newest entry in [state]'s history. */
    suspend fun undoLast(state: DeckState): DeckState =
        if (state.history.isEmpty()) state else revert(state, state.history.lastIndex)

    /**
     * Reverts the entry at [index] and drops it from the history, leaving every
     * other entry in force. An out-of-range index is a no-op.
     */
    suspend fun revert(state: DeckState, index: Int): DeckState {
        val action = state.history.getOrNull(index) ?: return state
        if (action is DeckAction.Move) undoMove(action)
        val history = state.history.toMutableList().also { it.removeAt(index) }
        return invert(state, action, history)
    }

    /** The inverse of one [DeckAction], mirroring [DeckReducer.undo]'s branch semantics. */
    private fun invert(state: DeckState, action: DeckAction, history: List<DeckAction>): DeckState =
        when (action) {
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

            is DeckAction.Defer -> invertDefer(state, action, history)

            // Reserved for targeted undo (§5.5); the reducer never emits it, so
            // there is nothing to invert — dropping it from the log is enough.
            is DeckAction.Undo -> state.copy(history = history)
        }

    private fun invertDefer(
        state: DeckState,
        action: DeckAction.Defer,
        history: List<DeckAction>,
    ): DeckState =
        if (action.requeued) {
            val at = state.items.lastIndexOf(action.item)
            val items = if (at >= 0) {
                state.items.without(at).insert(action.item, action.at)
            } else {
                state.items.insert(action.item, action.at)
            }
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

private fun List<ItemId>.without(index: Int): List<ItemId> =
    toMutableList().apply { removeAt(index) }

private fun List<ItemId>.insert(item: ItemId, index: Int): List<ItemId> =
    toMutableList().apply { add(index.coerceIn(0, size), item) }

private fun List<Deferred>.withCount(item: ItemId, count: Int): List<Deferred> {
    if (count <= 0) return filterNot { it.item == item }
    val index = indexOfFirst { it.item == item }
    if (index < 0) return this + Deferred(item, count)
    return toMutableList().apply { this[index] = Deferred(item, count) }
}
