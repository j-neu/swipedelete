package com.swipedelete.core.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.swipedelete.core.designsystem.R
import com.swipedelete.core.designsystem.SwipeDeleteDimens
import com.swipedelete.core.designsystem.SwipeDeleteTheme

/**
 * The staging strip (SPEC 5.2): a staged delete is visible and recoverable, not
 * one-frame-commit. It states the count and offers a text "Undo" affordance.
 * Presentation-only — the caller owns staging and undo.
 */
@Composable
fun StagingStrip(
    count: Int,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
    onApply: (() -> Unit)? = null,
) {
    val colors = SwipeDeleteTheme.colors
    val countText = stringResource(R.string.staging_strip_count, count)
    val undoLabel = stringResource(R.string.action_undo)
    val applyLabel = stringResource(R.string.action_apply)
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = colors.surfaceVariant,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = SwipeDeleteDimens.SpacingMedium,
                    vertical = SwipeDeleteDimens.SpacingSmall,
                ),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = countText,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(SwipeDeleteDimens.SpacingSmall),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onApply != null) {
                    TextButton(
                        onClick = onApply,
                        modifier = Modifier.defaultMinSize(
                            minWidth = SwipeDeleteDimens.MinTouchTarget,
                            minHeight = SwipeDeleteDimens.MinTouchTarget,
                        ),
                    ) {
                        Text(text = applyLabel)
                    }
                }
                TextButton(
                    onClick = onUndo,
                    modifier = Modifier.defaultMinSize(
                        minWidth = SwipeDeleteDimens.MinTouchTarget,
                        minHeight = SwipeDeleteDimens.MinTouchTarget,
                    ),
                ) {
                    Text(text = undoLabel)
                }
            }
        }
    }
}
