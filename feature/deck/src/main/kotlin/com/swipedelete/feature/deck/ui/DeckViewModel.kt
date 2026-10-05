package com.swipedelete.feature.deck.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swipedelete.core.common.Clock
import com.swipedelete.core.database.dao.DeckSessionDao
import com.swipedelete.core.database.dao.SlotDao
import com.swipedelete.core.model.DeckStatus
import com.swipedelete.core.model.ItemId
import com.swipedelete.core.model.MediaItem
import com.swipedelete.core.model.Slot
import com.swipedelete.core.model.SlotId
import com.swipedelete.core.storage.StorageGateway
import com.swipedelete.feature.deck.domain.DeckBuilder
import com.swipedelete.feature.deck.domain.DeckQuery
import com.swipedelete.feature.deck.domain.DeckState
import com.swipedelete.feature.deck.domain.Gesture
import com.swipedelete.feature.deck.domain.SlotMoveOutcome
import com.swipedelete.feature.deck.session.MutationGate
import com.swipedelete.feature.deck.session.SessionController
import com.swipedelete.feature.deck.session.SessionMutations
import com.swipedelete.feature.deck.session.SessionSummary
import com.swipedelete.feature.deck.session.StaleAbort
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.InputStream
import javax.inject.Inject
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
    val finished: Boolean = false,
    val summary: SessionSummary? = null,
    /** True after a drop on a slot whose destination grant is gone. */
    val moveUnavailable: Boolean = false,
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
    private val clock: Clock,
) : ViewModel() {

    private val controller = SessionController(
        sessions = sessions,
        builder = builder,
        clock = clock,
        gate = gate,
        mutations = mutations,
        measuredFreed = { 0L },
        reclaimable = { 0L },
        gateway = gateway,
    )

    /** The full [Slot] behind each dock slot, so a drop can perform the move. */
    private var slotModels: Map<SlotId, Slot> = emptyMap()

    private val _state = MutableStateFlow(DeckUiState())
    val state: StateFlow<DeckUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { startDefaultDeck() }
    }

    /** Builds a deck for the first available source, or shows the empty deck. */
    suspend fun startDefaultDeck() {
        val source = runCatching { gateway.sources() }.getOrDefault(emptyList()).firstOrNull()
        if (source == null) {
            _state.update { it.copy(loading = false, hasSource = false) }
            return
        }
        refresh(controller.start(DeckQuery.Folder(source.id)))
    }

    fun decide(gesture: Gesture) = viewModelScope.launch { refresh(controller.decide(gesture)) }

    fun onSlotSelected(dock: DockSlot) = viewModelScope.launch {
        val item = _state.value.current ?: return@launch
        val slot = slotModels[dock.id] ?: return@launch
        when (controller.applySlotMove(slot, item)) {
            is SlotMoveOutcome.DestinationUnavailable ->
                _state.update { it.copy(moveUnavailable = true) }
            else -> Unit
        }
        refresh()
    }

    fun applyStaged() = viewModelScope.launch {
        val result = controller.applyStaged()
        _state.update { it.copy(aborted = result.aborted) }
        refresh()
    }

    fun discardStaging() = viewModelScope.launch { refresh(controller.discardStaging()) }

    fun keepStaged() = viewModelScope.launch { refresh(controller.keepStaged()) }

    fun revert(index: Int) = viewModelScope.launch { refresh(controller.revert(index)) }

    fun undoLast() = viewModelScope.launch { refresh(controller.undoLast()) }

    fun finish() = viewModelScope.launch {
        controller.finish(DeckStatus.COMPLETED)
        refresh()
    }

    fun restoreEverything() = viewModelScope.launch {
        controller.restoreEverything()
        refresh()
    }

    fun dismissMoveUnavailable() = _state.update { it.copy(moveUnavailable = false) }

    fun dismissAborts() = _state.update { it.copy(aborted = emptyList()) }

    /** The stream for [item] through the same gateway the rest of the app uses. */
    fun openStream(item: MediaItem): InputStream = gateway.openStream(item)

    fun item(id: ItemId): MediaItem? = _state.value.items.firstOrNull { it.id == id }

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
