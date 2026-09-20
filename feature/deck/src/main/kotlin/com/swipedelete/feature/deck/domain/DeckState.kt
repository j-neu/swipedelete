package com.swipedelete.feature.deck.domain

import com.swipedelete.core.model.ItemId
import com.swipedelete.core.model.SlotId

/** An item the user deferred, with how many times it has been re-queued so far. */
data class Deferred(val item: ItemId, val count: Int)

/** A recorded deck transition that [DeckReducer.undo] can invert exactly. */
sealed interface DeckAction {
    data class Delete(val item: ItemId, val at: Int) : DeckAction
    data class Keep(val item: ItemId, val at: Int) : DeckAction
    data class Move(val item: ItemId, val slot: SlotId, val at: Int) : DeckAction
    data class Defer(
        val item: ItemId,
        val at: Int,
        val count: Int,
        val requeued: Boolean,
    ) : DeckAction

    /** Reserved for targeted undo of a history entry (§5.5); never emitted by [DeckReducer]. */
    data class Undo(val target: Int) : DeckAction
}

/**
 * The pure deck state machine (§9.2): no Android dependency, so it is
 * exhaustively unit- and property-testable.
 *
 * [items]/[cursor] are the remaining queue and the card being presented.
 * [staged], [kept] and [decided] are terminal and pairwise disjoint from each
 * other and from the queue; [decided] holds moves and items that exhausted
 * their re-queue budget ("decide later"). [deferred] is the defer ledger, and
 * [history] is the invertible action log.
 */
data class DeckState(
    val items: List<ItemId> = emptyList(),
    val cursor: Int = 0,
    val staged: List<ItemId> = emptyList(),
    val kept: List<ItemId> = emptyList(),
    val deferred: List<Deferred> = emptyList(),
    val decided: List<ItemId> = emptyList(),
    val history: List<DeckAction> = emptyList(),
)
