package com.swipedelete.feature.deck.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.swipedelete.core.common.formatBytes
import com.swipedelete.core.designsystem.SwipeDeleteDimens
import com.swipedelete.core.designsystem.SwipeDeleteTheme
import com.swipedelete.core.designsystem.components.DestructiveAction
import com.swipedelete.core.designsystem.components.StagingStrip as DesignSystemStagingStrip
import com.swipedelete.core.model.MediaItem
import com.swipedelete.feature.deck.R

/** The preview grid the Apply confirmation renders (SPEC 6.6). */
const val StagingPreviewGridTestTag: String = "staging_preview_grid"

/** One tile in the preview grid, one per staged item. */
const val StagingPreviewTileTestTag: String = "staging_preview_tile"

/** The Apply confirmation dialog content, so tests can address it directly. */
const val StagingApplyConfirmTestTag: String = "staging_apply_confirm"

/** How many tiles the confirmation previews before collapsing to "and N more". */
private const val PreviewTileLimit: Int = 12

/**
 * The deck-side staging strip (SPEC 5.2 / 6.6). It reuses the design system's
 * [DesignSystemStagingStrip] ("N to delete · Undo"), so a staged delete is
 * visible and recoverable, and it owns exactly one confirmation for Apply:
 * count, bytes and a preview grid of the staged set (§6.6).
 *
 * Presentation-only: it never touches storage. The caller supplies [onApply],
 * wired to the session-owned [com.swipedelete.feature.deck.session.SessionController.applyStaged]
 * path (there is exactly one Apply path; the old [StagingApplier] was removed).
 */
@Composable
fun StagingStrip(
    staged: List<MediaItem>,
    onUndo: () -> Unit,
    onApply: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Reset the pending confirmation whenever the staged set changes, so a stale
    // confirmation can never commit a set the user has not seen.
    var confirming by remember(staged) { mutableStateOf(false) }

    DesignSystemStagingStrip(
        count = staged.size,
        onUndo = onUndo,
        modifier = modifier,
        onApply = { if (staged.isNotEmpty()) confirming = true },
    )

    if (confirming) {
        StagingApplyConfirmation(
            staged = staged,
            onConfirm = {
                confirming = false
                onApply()
            },
            onDismiss = { confirming = false },
        )
    }
}

/**
 * The single Apply confirmation (SPEC 6.6): it names the count and the bytes and
 * previews the staged set. Confirming is destructive, so it renders through
 * [DestructiveAction] (icon + text + destructive colour, SPEC 11.4).
 */
@Composable
internal fun StagingApplyConfirmation(
    staged: List<MediaItem>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val count = staged.size
    val bytes = staged.sumOf { it.sizeBytes }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.staging_apply_confirm_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(StagingApplyConfirmTestTag),
                verticalArrangement = Arrangement.spacedBy(SwipeDeleteDimens.SpacingSmall),
            ) {
                Text(
                    text = stringResource(
                        R.string.staging_apply_confirm_message,
                        count,
                        formatBytes(bytes),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = SwipeDeleteTheme.colors.onSurfaceVariant,
                )
                StagingPreviewGrid(staged)
            }
        },
        confirmButton = {
            DestructiveAction(
                text = stringResource(R.string.staging_apply_confirm_action),
                onClick = onConfirm,
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.staging_apply_cancel))
            }
        },
    )
}

/** A bounded grid of the staged items' thumbnails (SPEC 6.6). */
@Composable
private fun StagingPreviewGrid(staged: List<MediaItem>) {
    val shown = staged.take(PreviewTileLimit)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(StagingPreviewGridTestTag),
        verticalArrangement = Arrangement.spacedBy(SwipeDeleteDimens.SpacingSmall),
    ) {
        shown.chunked(4).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(SwipeDeleteDimens.SpacingSmall)) {
                rowItems.forEach { item -> StagingPreviewTile(item) }
            }
        }
        val hidden = staged.size - shown.size
        if (hidden > 0) {
            Text(
                text = stringResource(R.string.staging_apply_more, hidden),
                style = MaterialTheme.typography.bodySmall,
                color = SwipeDeleteTheme.colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StagingPreviewTile(item: MediaItem) {
    val label = item.path?.substringAfterLast('/')?.takeIf { it.isNotEmpty() } ?: item.id.value
    Box(
        modifier = Modifier
            .size(48.dp)
            .testTag(StagingPreviewTileTestTag)
            .semantics { contentDescription = label },
    ) {
        Thumbnail(item = item, modifier = Modifier.fillMaxSize())
    }
}
