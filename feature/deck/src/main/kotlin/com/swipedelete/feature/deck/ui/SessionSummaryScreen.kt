package com.swipedelete.feature.deck.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.swipedelete.core.designsystem.SwipeDeleteDimens
import com.swipedelete.core.designsystem.SwipeDeleteTheme
import com.swipedelete.core.designsystem.components.DestructiveAction
import com.swipedelete.core.designsystem.components.PrimaryButton
import com.swipedelete.feature.deck.R
import com.swipedelete.feature.deck.session.ExitPrompt
import com.swipedelete.feature.deck.session.SessionSummary

/**
 * The session summary (SPEC 5.5 / M1-T13, AC-9).
 *
 * Presentation-only: it renders the [SessionSummary] the session layer built and
 * hoists every action back to the caller. The two byte figures are never
 * formatted here — [SessionSummary.reclaimableText] / [SessionSummary.freedText]
 * already came from `formatReclaimable` / `formatFreed`, the only functions
 * allowed to render either phrase (AC-9). Quarantine bytes are shown as
 * *reclaimable*; the measured-freed figure appears only when a permanent delete
 * actually ran, so an all-quarantine session never claims anything was freed.
 *
 * The "restore everything from this session" panic button is the prominent
 * primary action (SPEC 5.5), and a non-empty staging strip raises the exit
 * prompt: Apply / Keep staged / Discard.
 */
@Composable
fun SessionSummaryScreen(
    summary: SessionSummary,
    onRestoreEverything: () -> Unit,
    modifier: Modifier = Modifier,
    exitPrompt: ExitPrompt? = null,
    onApply: () -> Unit = {},
    onKeepStaged: () -> Unit = {},
    onDiscardStaging: () -> Unit = {},
) {
    val colors = SwipeDeleteTheme.colors
    Surface(modifier = modifier.fillMaxWidth(), color = colors.surface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SwipeDeleteDimens.SpacingMedium),
            verticalArrangement = Arrangement.spacedBy(SwipeDeleteDimens.SpacingSmall),
        ) {
            Text(
                text = stringResource(R.string.session_summary_title),
                style = MaterialTheme.typography.headlineSmall,
                color = colors.onSurface,
            )
            SummaryLine(stringResource(R.string.session_summary_reviewed, summary.reviewed))
            SummaryLine(stringResource(R.string.session_summary_kept, summary.kept))
            summary.movedBySlot.entries.sortedBy { it.key.value }.forEach { (slot, count) ->
                SummaryLine(stringResource(R.string.session_summary_moved_to_slot, count, slot.value))
            }
            SummaryLine(stringResource(R.string.session_summary_staged, summary.staged))
            SummaryLine(stringResource(R.string.session_summary_quarantined, summary.quarantined))
            SummaryLine(stringResource(R.string.session_summary_deferred, summary.deferred))
            SummaryLine(summary.reclaimableText)
            if (summary.measuredFreedBytes > 0L) {
                SummaryLine(summary.freedText)
            }

            Spacer(Modifier.height(SwipeDeleteDimens.SpacingSmall))
            PrimaryButton(
                onClick = onRestoreEverything,
                text = stringResource(R.string.session_summary_restore_everything),
                modifier = Modifier.fillMaxWidth(),
            )

            if (exitPrompt != null) {
                Spacer(Modifier.height(SwipeDeleteDimens.SpacingSmall))
                Text(
                    text = stringResource(R.string.session_summary_exit_prompt, exitPrompt.staged),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(SwipeDeleteDimens.SpacingSmall)) {
                    DestructiveAction(
                        text = stringResource(R.string.session_summary_apply),
                        onClick = onApply,
                    )
                    PrimaryButton(
                        onClick = onKeepStaged,
                        text = stringResource(R.string.session_summary_keep_staged),
                    )
                    TextButton(onClick = onDiscardStaging) {
                        Text(text = stringResource(R.string.session_summary_discard))
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = SwipeDeleteTheme.colors.onSurface,
    )
}
