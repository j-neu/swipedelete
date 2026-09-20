package com.swipedelete.feature.settings.onboarding

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swipedelete.core.database.dao.SlotDao
import com.swipedelete.core.database.entity.SlotEntity
import com.swipedelete.core.model.Slot
import com.swipedelete.core.model.SlotId
import com.swipedelete.core.model.SlotMode
import com.swipedelete.core.model.SourceRef
import com.swipedelete.feature.settings.grants.GrantMutator
import com.swipedelete.feature.settings.grants.PersistResult
import com.swipedelete.feature.settings.grants.SourceGrantRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the onboarding screen renders. */
data class OnboardingState(
    val slots: List<Slot> = emptyList(),
    val completed: Boolean = false,
    val error: OnboardingError? = null,
) {
    val maxSlots: Int get() = MAX_SLOTS

    /** Explicit refusal signal, so the UI never has to infer a silent drop. */
    val slotLimitReached: Boolean get() = error is OnboardingError.SlotLimitReached
}

/** Every way onboarding can refuse: each is stated, none is silent. */
sealed interface OnboardingError {

    /** A sixth slot was requested; [max] is the limit that was enforced. */
    data class SlotLimitReached(val max: Int) : OnboardingError

    /** The platform refused the grant, or the app's grant cap was reached. */
    data class GrantRefused(val capReached: Boolean) : OnboardingError
}

/**
 * SPEC 4.5: "pick your five folders" is the app's first permission request and
 * nothing more dramatic than a folder picker — no separate permission wall, and
 * no permission needed to finish. AC-19 is enforced here in two directions:
 *
 * - [skip] completes onboarding with zero slots and no error, because a user who
 *   grants nothing still has a usable Tier 0 app;
 * - slots are appended to Room through [SlotDao] and a sixth is refused
 *   explicitly ([OnboardingError.SlotLimitReached]) rather than dropped.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val slots: SlotDao,
    private val grants: SourceGrantRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    init {
        viewModelScope.launch { reload() }
    }

    /**
     * Called with the `Uri` the system tree picker returned. The grant is
     * persisted first; only a persisted grant becomes a slot.
     */
    fun onFolderPicked(uri: Uri, flags: Int = GrantMutator.FLAG_READ_WRITE) {
        viewModelScope.launch {
            when (val result = grants.persist(uri.toString(), flags)) {
                is PersistResult.Persisted -> addSlot(result.source)
                PersistResult.GrantCapReached ->
                    _state.update { it.copy(error = OnboardingError.GrantRefused(capReached = true)) }
                PersistResult.GrantDenied ->
                    _state.update { it.copy(error = OnboardingError.GrantRefused(capReached = false)) }
            }
        }
    }

    /**
     * Adds a destination slot for [source]. Returns `false` — and records
     * [OnboardingError.SlotLimitReached] — when [MAX_SLOTS] are already set.
     */
    suspend fun addSlot(source: SourceRef): Boolean {
        val current = slots.all()
        if (current.size >= MAX_SLOTS) {
            _state.update { it.copy(error = OnboardingError.SlotLimitReached(MAX_SLOTS)) }
            return false
        }
        val nextId = (current.maxOfOrNull { it.id } ?: -1) + 1
        slots.upsert(
            SlotEntity(
                id = nextId,
                name = source.displayName,
                icon = DEFAULT_SLOT_ICON,
                colour = DEFAULT_SLOT_COLOUR,
                destinationUri = source.treeUri,
                mode = SlotMode.MOVE,
                // sortOrder tracks the id, so removing a slot never collides with
                // a later one and the visible order is always append order.
                sortOrder = nextId,
            ),
        )
        _state.update { it.copy(slots = storedSlots(), error = null) }
        return true
    }

    /** Removes a slot; a removed slot frees room under [MAX_SLOTS]. */
    fun removeSlot(id: SlotId) {
        viewModelScope.launch {
            slots.delete(id.value)
            _state.update { it.copy(slots = storedSlots(), error = null) }
        }
    }

    /**
     * AC-19: the flow is skippable. Completing with zero slots is a valid,
     * error-free outcome — the app runs at Tier 0 with no grants at all.
     */
    fun skip() {
        _state.update { it.copy(completed = true, error = null) }
    }

    /** Completes with whatever slots were picked, including none. */
    fun complete() {
        _state.update { it.copy(completed = true, error = null) }
    }

    private suspend fun reload() {
        _state.update { it.copy(slots = storedSlots()) }
    }

    private suspend fun storedSlots(): List<Slot> = slots.all().map { it.toSlot() }

    private companion object {
        const val DEFAULT_SLOT_ICON: String = "folder"
        const val DEFAULT_SLOT_COLOUR: Long = 0L
    }
}
