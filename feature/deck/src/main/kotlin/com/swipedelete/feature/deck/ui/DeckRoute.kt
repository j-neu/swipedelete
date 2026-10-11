package com.swipedelete.feature.deck.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swipedelete.core.designsystem.SwipeDeleteTheme
import com.swipedelete.core.model.ItemId
import com.swipedelete.feature.deck.R
import com.swipedelete.feature.deck.domain.Gesture
import com.swipedelete.feature.deck.session.ExitPrompt

/** Task 30: the banner a capped scan raises, so a partial deck is visible. */
const val DeckPartialBannerTestTag: String = "deck_partial_banner"

/** AC-8: the banner that names the items an Apply refused because they changed. */
const val DeckStaleAbortBannerTestTag: String = "deck_stale_abort_banner"

/** The banner for a deck action that failed outright. */
const val DeckErrorBannerTestTag: String = "deck_error_banner"

/** The banner that names the items an Apply could not move, and why. */
const val DeckApplyFailedBannerTestTag: String = "deck_apply_failed_banner"

/**
 * The assembled deck route (finding M1-T15-1). It renders the real deck
 * components — the swipe card, the staging strip, the history strip, the slot
 * dock and the session summary — over [DeckViewModel]'s state, and hoists the
 * one navigation event (open the viewer for an item) to the shell.
 *
 * [viewModel] is a `@HiltViewModel`; production uses the default `hiltViewModel()`,
 * and a test constructs one with fakes and calls [DeckRouteContent] directly.
 */
@Composable
fun DeckRoute(
    onOpenItem: (ItemId) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DeckViewModel = hiltViewModel(),
    topContent: @Composable () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DeckRouteContent(
        state = state,
        onDecision = viewModel::decide,
        onOpen = onOpenItem,
        onSlotSelected = viewModel::onSlotSelected,
        onApply = viewModel::applyStaged,
        onDiscard = viewModel::discardStaging,
        onKeep = viewModel::keepStaged,
        onUndoLast = viewModel::undoLast,
        onRevert = viewModel::revert,
        onFinish = viewModel::finish,
        onRestore = viewModel::restoreEverything,
        onDismissAborts = viewModel::dismissAborts,
        onDismissFailures = viewModel::dismissFailures,
        onDismissError = viewModel::dismissError,
        onDismissMoveUnavailable = viewModel::dismissMoveUnavailable,
        modifier = modifier,
        topContent = topContent,
    )
}

/**
 * The deck route's pure presentation: state in, callbacks out. Kept separate
 * from the Hilt entry point so the composed surface is testable without Hilt.
 */
@Composable
fun DeckRouteContent(
    state: DeckUiState,
    onDecision: (Gesture) -> Unit,
    onOpen: (ItemId) -> Unit,
    onSlotSelected: (DockSlot) -> Unit,
    onApply: () -> Unit,
    onDiscard: () -> Unit,
    onKeep: () -> Unit,
    onUndoLast: () -> Unit,
    onRevert: (Int) -> Unit,
    onFinish: () -> Unit,
    onRestore: () -> Unit,
    onDismissAborts: () -> Unit = {},
    onDismissFailures: () -> Unit = {},
    onDismissError: () -> Unit = {},
    onDismissMoveUnavailable: () -> Unit = {},
    modifier: Modifier = Modifier,
    topContent: @Composable () -> Unit = {},
) {
    var dockRevealed by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        topContent()

        if (state.partialDeck) {
            NoticeBanner(
                text = stringResource(R.string.deck_partial_banner, state.scanned),
                tag = DeckPartialBannerTestTag,
            )
        }

        state.error?.let { error ->
            NoticeBanner(
                text = stringResource(R.string.deck_error, error),
                tag = DeckErrorBannerTestTag,
                onDismiss = onDismissError,
            )
        }

        if (state.moveUnavailable) {
            NoticeBanner(
                text = stringResource(R.string.deck_move_unavailable),
                onDismiss = onDismissMoveUnavailable,
            )
        }

        if (state.aborted.isNotEmpty()) {
            NoticeBanner(
                text = stringResource(
                    R.string.deck_stale_abort,
                    state.aborted.size,
                    state.aborted.joinToString("; ") { it.reason },
                ),
                tag = DeckStaleAbortBannerTestTag,
                onDismiss = onDismissAborts,
            )
        }

        if (state.failed.isNotEmpty()) {
            val reasons = state.failed.map { it.reason }.distinct().map { applyFailureReason(it) }
            NoticeBanner(
                text = stringResource(
                    R.string.deck_apply_failed,
                    state.failed.size,
                    reasons.joinToString("; "),
                ),
                tag = DeckApplyFailedBannerTestTag,
                onDismiss = onDismissFailures,
            )
        }

        if (state.finished && state.summary != null) {
            SessionSummaryScreen(
                summary = state.summary,
                onRestoreEverything = onRestore,
                modifier = Modifier.weight(1f),
                exitPrompt = if (state.staged.isNotEmpty()) ExitPrompt(state.staged.size) else null,
                onApply = onApply,
                onKeepStaged = onKeep,
                onDiscardStaging = onDiscard,
            )
            return@Column
        }

        DeckScreen(
            item = state.current,
            onDecision = onDecision,
            onMove = { dockRevealed = true },
            modifier = Modifier.weight(1f),
            onOpen = { state.current?.let { onOpen(it.id) } },
            bottomContent = {
                if (state.staged.isNotEmpty()) {
                    StagingStrip(staged = state.staged, onUndo = onUndoLast, onApply = onApply)
                }
                HistoryStrip(history = state.deck.history, onRevert = onRevert)
                if (dockRevealed) {
                    DeckSlotDock(
                        slots = state.slots,
                        onSlotSelected = { slot ->
                            dockRevealed = false
                            onSlotSelected(slot)
                        },
                    )
                }
                // The only route to the summary, the exit prompt and restore-everything.
                if (state.hasSource) {
                    TextButton(onClick = onFinish) {
                        Text(text = stringResource(R.string.deck_finish))
                    }
                }
            },
        )
    }
}

/** A plain-language reason for a gateway failure code; an unknown code is shown as is. */
@Composable
private fun applyFailureReason(reason: String): String = when (reason) {
    "protected_root" -> stringResource(R.string.deck_reason_protected)
    "insufficient_space" -> stringResource(R.string.deck_reason_space)
    "original_delete_failed" -> stringResource(R.string.deck_reason_delete_refused)
    else -> reason
}

@Composable
private fun NoticeBanner(
    text: String,
    tag: String? = null,
    onDismiss: (() -> Unit)? = null,
) {
    val colors = SwipeDeleteTheme.colors
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
        color = colors.surfaceVariant,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (onDismiss != null) {
                TextButton(onClick = onDismiss) {
                    Text(text = stringResource(R.string.deck_dismiss))
                }
            }
        }
    }
}
