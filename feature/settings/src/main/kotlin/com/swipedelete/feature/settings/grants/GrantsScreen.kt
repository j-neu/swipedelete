package com.swipedelete.feature.settings.grants

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.swipedelete.core.designsystem.SwipeDeleteDimens
import com.swipedelete.core.designsystem.components.EmptyState
import com.swipedelete.core.model.Slot
import com.swipedelete.core.model.SourceId
import com.swipedelete.core.model.SourceRef
import com.swipedelete.feature.settings.R

/** What the grants screen renders: the five slots and every persisted grant. */
data class GrantsState(
    val grants: List<SourceRef> = emptyList(),
    val slots: List<Slot> = emptyList(),
    val maxGrants: Int = SourceGrantRepository.MAX_GRANTS,
)

/**
 * SPEC 4.5: every grant is listed with its display name, its access mode and a
 * one-tap revoke; the cap is stated as usage rather than as a wall. An empty
 * grant set is an explainable, usable state (AC-19), not an error.
 *
 * The five destination slots are shown alongside the source grants because
 * together they are the whole permission model.
 */
@Composable
fun GrantsScreen(
    state: GrantsState,
    onRevoke: (SourceId) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(SwipeDeleteDimens.SpacingMedium),
        verticalArrangement = Arrangement.spacedBy(SwipeDeleteDimens.SpacingMedium),
    ) {
        Text(
            text = stringResource(R.string.grants_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.grants_body),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(R.string.grants_cap_usage, state.grants.size, state.maxGrants),
            style = MaterialTheme.typography.labelLarge,
        )

        Text(
            text = stringResource(R.string.grants_slots_title),
            style = MaterialTheme.typography.titleMedium,
        )
        if (state.slots.isEmpty()) {
            Text(
                text = stringResource(R.string.grants_slot_empty),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            state.slots.forEach { slot ->
                Text(text = slot.name, style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (state.grants.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.grants_empty_title),
                body = stringResource(R.string.grants_empty_body),
            )
        } else {
            state.grants.forEach { source ->
                GrantRow(source = source, onRevoke = onRevoke)
            }
        }
    }
}
