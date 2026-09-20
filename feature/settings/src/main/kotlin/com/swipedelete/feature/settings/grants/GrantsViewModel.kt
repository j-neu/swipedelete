package com.swipedelete.feature.settings.grants

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.swipedelete.core.database.dao.SlotDao
import com.swipedelete.core.model.SourceId
import com.swipedelete.feature.settings.onboarding.toSlot
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The grants/slots screen's state. It combines the two halves of the permission
 * model (SPEC 4.5): the persisted grants the platform reports and the five
 * destination slots stored in Room.
 */
@HiltViewModel
class GrantsViewModel @Inject constructor(
    private val grants: SourceGrantRepository,
    private val slots: SlotDao,
) : ViewModel() {

    val state: StateFlow<GrantsState> = combine(
        grants.grants(),
        slots.observeAll(),
    ) { sources, slotRows ->
        GrantsState(
            grants = sources,
            slots = slotRows.map { it.toSlot() },
            maxGrants = grants.maxGrants,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SETTLE_MILLIS),
        initialValue = GrantsState(),
    )

    /** One-tap revoke: drops the app's access, never a file. */
    fun revoke(id: SourceId) {
        viewModelScope.launch { grants.revoke(id) }
    }

    private companion object {
        const val SETTLE_MILLIS: Long = 5_000L
    }
}

@Composable
fun GrantsRoute(
    modifier: Modifier = Modifier,
    viewModel: GrantsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    GrantsScreen(state = state, onRevoke = viewModel::revoke, modifier = modifier)
}
