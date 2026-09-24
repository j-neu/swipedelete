package com.swipedelete.feature.deck.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.swipedelete.core.designsystem.SwipeDeleteDimens
import com.swipedelete.core.designsystem.SwipeDeleteTheme
import com.swipedelete.feature.deck.R
import com.swipedelete.feature.deck.domain.DeckAction

/**
 * SPEC 5.5: the session-wide history strip. Every action the session recorded is
 * a chip in a horizontally scrollable strip, and tapping a chip reverts *that*
 * entry by index — the whole session is undoable, not just the newest action.
 *
 * Presentation-only: the caller owns the deck state and the revert behaviour
 * ([com.swipedelete.feature.deck.session.UndoStack]).
 */
@Composable
fun HistoryStrip(
    history: List<DeckAction>,
    onRevert: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (history.isEmpty()) return
    val colors = SwipeDeleteTheme.colors
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = colors.surfaceVariant,
    ) {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(
                horizontal = SwipeDeleteDimens.SpacingMedium,
                vertical = SwipeDeleteDimens.SpacingSmall,
            ),
            horizontalArrangement = Arrangement.spacedBy(SwipeDeleteDimens.SpacingSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            itemsIndexed(history) { index, action ->
                HistoryChip(action = action, onClick = { onRevert(index) })
            }
        }
    }
}

@Composable
private fun HistoryChip(action: DeckAction, onClick: () -> Unit) {
    val colors = SwipeDeleteTheme.colors
    Surface(
        onClick = onClick,
        modifier = Modifier.defaultMinSize(minHeight = SwipeDeleteDimens.MinTouchTarget),
        shape = RoundedCornerShape(percent = 50),
        color = colors.surface,
        contentColor = colors.onSurfaceVariant,
    ) {
        Text(
            text = historyLabel(action),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(
                horizontal = SwipeDeleteDimens.SpacingMedium,
                vertical = SwipeDeleteDimens.SpacingSmall,
            ),
        )
    }
}

@Composable
private fun historyLabel(action: DeckAction): String =
    when (action) {
        is DeckAction.Delete -> stringResource(R.string.history_entry_staged, action.item.value)
        is DeckAction.Keep -> stringResource(R.string.history_entry_kept, action.item.value)
        is DeckAction.Move -> stringResource(
            R.string.history_entry_moved,
            action.item.value,
            action.slot.value,
        )

        is DeckAction.Defer -> stringResource(R.string.history_entry_deferred, action.item.value)
        is DeckAction.Undo -> stringResource(R.string.history_entry_undo)
    }
