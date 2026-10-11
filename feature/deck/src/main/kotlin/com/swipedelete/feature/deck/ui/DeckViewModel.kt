package com.swipedelete.feature.deck.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swipedelete.core.common.Clock
import com.swipedelete.core.database.dao.DeckSessionDao
import com.swipedelete.core.database.dao.SlotDao
import com.swipedelete.core.model.DeckStatus
import com.swipedelete.core.model.ItemId
import com.swipedelete.core.model.MediaItem
import com.swipedelete.core.model.SessionId
import com.swipedelete.core.model.Slot
import com.swipedelete.core.model.SlotId
import com.swipedelete.core.storage.StorageGateway
import com.swipedelete.feature.deck.domain.DeckBuilder
import com.swipedelete.feature.deck.domain.DeckQuery
import com.swipedelete.feature.deck.domain.DeckState
import com.swipedelete.feature.deck.domain.Gesture
import com.swipedelete.feature.deck.domain.SlotMoveOutcome
import com.swipedelete.feature.deck.domain.SlotTagSink
import com.swipedelete.feature.deck.session.ApplyFailure
import com.swipedelete.feature.deck.session.MutationGate
import com.swipedelete.feature.deck.session.SessionController
import com.swipedelete.feature.deck.session.SessionMutations
import com.swipedelete.feature.deck.session.SessionSummary
import com.swipedelete.feature.deck.session.StaleAbort
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.InputStream
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Everything the deck surface renders. Presentation state only; the pure deck
 * engine in `domain/` remains the single source of truth for the queue.
 */
data class DeckUiState(
    val loading: Boolean = true,
    val hasSource: Boolean = false,
    /** The deck's frozen items, in deck order (the viewer pages through these). */
    val items: List<MediaItem> = emptyList(),
    val current: MediaItem? = null,
    val deck: DeckState = DeckState(),
    val staged: List<MediaItem> = emptyList(),
    val slots: List<DockSlot> = emptyList(),
    /** Task 30 / §9.4: true when the scan was capped, so the deck is partial. */
    val partialDeck: Boolean = false,
    val scanned: Int = 0,
    /** AC-8: items skipped by Apply because they changed on disk, with reasons. */
    val aborted: List<StaleAbort> = emptyList(),
    /** Items an Apply could not move; still on disk and still staged, with reasons. */
    val failed: List<ApplyFailure> = emptyList(),
    val finished: Boolean = false,
    val summary: SessionSummary? = null,
    /** True after a drop on a slot whose destination grant is gone. */
    val moveUnavailable: Boolean = false,
    /** The last failure of a deck action, shown to the user rather than swallowed. */
    val error: String? = null,
    /** How many items the last "restore everything" left in quarantine. */
    val notRestored: Int = 0,
)

/**
 * The assembled deck (finding M1-T15-1): it owns the deck state, a
 * [SessionController] bound to the app's [MutationGate] and [SessionMutations],
 * and the pager/summary state the deck route renders.
 *
 * Android/Hilt wiring lives here, not in the pure engine (D5). Every destructive
 * verb runs through the session's gated paths; the view model only maps results
 * to UI state.
 */
@HiltViewModel
class DeckViewModel @Inject constructor(
    private val builder: DeckBuilder,
    private val gateway: StorageGateway,
    private val sessions: DeckSessionDao,
    private val slots: SlotDao,
    private val gate: MutationGate,
    private val mutations: SessionMutations,
    private val tags: SlotTagSink,
    private val clock: Clock,
) : ViewModel() {

    private val controller = SessionController(
        sessions = sessions,
        builder = builder,
        clock = clock,
        gate = gate,
        mutations = mutations,
        measuredFreed = { 0L },
        gateway = gateway,
        tags = tags,
    )

    /** The full [Slot] behind each dock slot, so a drop can perform the move. */
    private var slotModels: Map<SlotId, Slot> = emptyMap()

    private val _state = MutableStateFlow(DeckUiState())
    val state: StateFlow<DeckUiState> = _state.asStateFlow()

    init {
        act { startDefaultDeck() }
    }

    /**
     * Resumes the session a crash or exit left behind (SPEC 5.5), or else builds a
     * deck for the first available source, or shows the empty deck.
     */
    suspend fun startDefaultDeck() {
        if (resumeActiveSession()) return
        val source = runCatching { gateway.sources() }.getOrDefault(emptyList()).firstOrNull()
        if (source == null) {
            _state.update { it.copy(loading = false, hasSource = false) }
            return
        }
        refresh(controller.start(DeckQuery.Folder(source.id)))
    }

    /**
     * True when an ACTIVE session with work left was restored. One that cannot be
     * restored, or has nothing left to do, is closed so it is not offered again.
     */
    private suspend fun resumeActiveSession(): Boolean {
        val active = sessions.latestActive() ?: return false
        val resumed = try {
            controller.resume(SessionId(active.id))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            null
        }
        val hasWorkLeft = resumed != null &&
            controller.snapshotItems().isNotEmpty() &&
            (resumed.cursor < resumed.items.size || resumed.staged.isNotEmpty())
        if (!hasWorkLeft) {
            sessions.markStatus(active.id, DeckStatus.ABANDONED)
            return false
        }
        refresh(resumed)
        return true
    }

    fun decide(gesture: Gesture) = act { refresh(controller.decide(gesture)) }

    fun onSlotSelected(dock: DockSlot) = act {
        val item = _state.value.current ?: return@act
        val slot = slotModels[dock.id] ?: return@act
        when (val outcome = controller.applySlotMove(slot, item)) {
            is SlotMoveOutcome.DestinationUnavailable ->
                _state.update { it.copy(moveUnavailable = true) }
            // A move that did not happen is said so, with the reason.
            is SlotMoveOutcome.Stale -> _state.update { it.copy(error = outcome.reason) }
            is SlotMoveOutcome.Failed -> _state.update { it.copy(error = outcome.reason) }
            is SlotMoveOutcome.Moved, is SlotMoveOutcome.Renamed, is SlotMoveOutcome.Tagged -> Unit
        }
        refresh()
    }

    fun applyStaged() = act {
        val result = controller.applyStaged()
        _state.update { it.copy(aborted = result.aborted, failed = result.failed) }
        refresh()
    }

    fun discardStaging() = act { refresh(controller.discardStaging()) }

    fun keepStaged() = act { refresh(controller.keepStaged()) }

    fun revert(index: Int) = act { refresh(controller.revert(index)) }

    fun undoLast() = act { refresh(controller.undoLast()) }

    fun finish() = act {
        controller.finish(DeckStatus.COMPLETED)
        refresh()
    }

    fun restoreEverything() = act {
        val requested = controller.summary().restorable
        val restored = controller.restoreEverything()
        // A restore that leaves items behind is reported; it is never a silent no-op.
        _state.update { it.copy(notRestored = requested - restored) }
        refresh()
    }

    fun dismissNotRestored() = _state.update { it.copy(notRestored = 0) }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun dismissMoveUnavailable() = _state.update { it.copy(moveUnavailable = false) }

    fun dismissAborts() = _state.update { it.copy(aborted = emptyList()) }

    fun dismissFailures() = _state.update { it.copy(failed = emptyList()) }

    /** The stream for [item] through the same gateway the rest of the app uses. */
    fun openStream(item: MediaItem): InputStream = gateway.openStream(item)

    fun item(id: ItemId): MediaItem? = _state.value.items.firstOrNull { it.id == id }

    /**
     * Runs one deck action. A failure (a revoked grant, an unreadable journal) is
     * reported in [DeckUiState.error] instead of crashing the app, and the deck
     * is left as it was.
     */
    private fun act(block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            _state.update {
                it.copy(loading = false, error = failure.message ?: failure::class.simpleName)
            }
        }
    }

    private suspend fun refresh(state: DeckState = controller.currentState()) {
        val items = controller.snapshotItems()
        slotModels = loadSlotModels()
        val liveDestinations = runCatching { gateway.sources() }
            .getOrDefault(emptyList())
            .mapTo(mutableSetOf()) { it.treeUri }
        val slots = slotModels.values.map { slot ->
            DockSlot(
                id = slot.id,
                name = slot.name,
                destinationUri = slot.destinationUri,
                // A slot with no destination is a TAG slot and is always usable;
                // a MOVE slot is only usable while its destination grant is live.
                granted = slot.destinationUri == null || slot.destinationUri in liveDestinations,
                unavailableReason = null,
            )
        }
        val currentId = state.items.getOrNull(state.cursor)
        val finished = controller.currentSession()?.status == DeckStatus.COMPLETED
        _state.update { previous ->
            previous.copy(
                loading = false,
                hasSource = true,
                items = items,
                current = currentId?.let { id -> items.firstOrNull { it.id == id } },
                deck = state,
                staged = state.staged.mapNotNull { id -> items.firstOrNull { it.id == id } },
                slots = slots,
                partialDeck = controller.snapshotTruncated(),
                scanned = controller.snapshotScanned(),
                finished = finished,
                summary = if (finished) controller.summary() else null,
            )
        }
    }

    private suspend fun loadSlotModels(): Map<SlotId, Slot> {
        val entities = runCatching { slots.all() }.getOrDefault(emptyList())
        return entities.associate { entity ->
            SlotId(entity.id) to Slot(
                id = SlotId(entity.id),
                name = entity.name,
                icon = entity.icon,
                colour = entity.colour,
                destinationUri = entity.destinationUri,
                mode = entity.mode,
                sortOrder = entity.sortOrder,
            )
        }
    }
}
