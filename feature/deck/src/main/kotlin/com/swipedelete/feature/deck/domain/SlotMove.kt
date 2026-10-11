package com.swipedelete.feature.deck.domain

import com.swipedelete.core.model.ItemId
import com.swipedelete.core.model.MediaItem
import com.swipedelete.core.model.Slot
import com.swipedelete.core.model.SlotId
import com.swipedelete.core.model.SlotMode
import com.swipedelete.core.model.Tag
import com.swipedelete.core.storage.Destination
import com.swipedelete.core.storage.IndividuallyReviewed
import com.swipedelete.core.storage.MoveResult
import com.swipedelete.core.storage.StorageGateway
import com.swipedelete.feature.deck.session.MutationGate
import kotlinx.coroutines.withContext

/**
 * Decision 4 (SPEC 5.3): a slot whose destination is set moves the file by
 * default; a slot with no destination can only tag it. The configured
 * [Slot.mode] is deliberately ignored — it is a presentation preference, and a
 * MOVE slot without a destination would be a dead end (AC-19).
 */
fun effectiveSlotMode(destinationUri: String?): SlotMode =
    if (destinationUri != null) SlotMode.MOVE else SlotMode.TAG

fun effectiveSlotMode(slot: Slot): SlotMode = effectiveSlotMode(slot.destinationUri)

/** Persists a TAG-only slot's tag. The file itself is never touched (SPEC 5.3). */
fun interface SlotTagSink {
    suspend fun tag(tag: Tag)

    /** Removes a tag again when the user undoes the decision. */
    suspend fun untag(itemId: ItemId, slotId: SlotId) = Unit

    companion object {
        val NoOp: SlotTagSink = SlotTagSink { }
    }
}

/** The outcome of dropping one item on one slot. */
sealed interface SlotMoveOutcome {
    /** The item landed under its original name. */
    data class Moved(val to: MediaItem) : SlotMoveOutcome

    /** The name was taken; [to] carries the ` (n)`-suffixed result, never an overwrite. */
    data class Renamed(val to: MediaItem) : SlotMoveOutcome

    /** The slot is TAG-only; only a [Tag] was written. */
    data class Tagged(val slot: SlotId) : SlotMoveOutcome

    /** The slot's destination is not among the live grants (e.g. a revoked grant). */
    data class DestinationUnavailable(val destinationUri: String?) : SlotMoveOutcome

    /** The item changed on disk since the deck was built, so it was not moved (AC-8). */
    data class Stale(val reason: String) : SlotMoveOutcome

    data class Failed(val reason: String) : SlotMoveOutcome
}

/**
 * Applies a slot to a single item (SPEC 5.3 / M1-T06).
 *
 * Every byte movement is delegated to the frozen [StorageGateway]: the
 * no-overwrite collision rename (` (1)`, ` (2)`, …) and the cross-volume
 * copy -> verify -> delete cancellation semantics are the gateway's contract
 * (AC-15), so this class contains no move logic of its own. A TAG slot never
 * reaches the gateway at all.
 *
 * A MOVE is a destructive verb (D6), so it waits on [MutationGate] before the
 * gateway call — a move cannot run before recovery reconciliation completes.
 */
class SlotMoveCoordinator(
    private val gateway: StorageGateway,
    private val tags: SlotTagSink = SlotTagSink.NoOp,
    private val nowMillis: () -> Long = { 0L },
    private val gate: MutationGate = MutationGate { },
) {

    suspend fun apply(slot: Slot, item: MediaItem): SlotMoveOutcome {
        if (effectiveSlotMode(slot) == SlotMode.TAG) {
            tags.tag(Tag(itemId = item.id, slotId = slot.id, taggedAt = nowMillis()))
            return SlotMoveOutcome.Tagged(slot.id)
        }

        val destination = slot.destinationUri?.let { uri ->
            gateway.sources().firstOrNull { it.treeUri == uri }
        } ?: return SlotMoveOutcome.DestinationUnavailable(slot.destinationUri)

        gate.await()
        // One card, dropped by the user on one slot: an individually reviewed item (AC-7).
        val result = withContext(IndividuallyReviewed(setOf(item.id))) {
            gateway.move(item, Destination(destination))
        }
        return when (result) {
            is MoveResult.Moved -> SlotMoveOutcome.Moved(result.to)
            is MoveResult.NameCollision -> SlotMoveOutcome.Renamed(result.resolvedTo)
            is MoveResult.Failed -> SlotMoveOutcome.Failed(result.reason)
        }
    }
}
