package com.swipedelete.feature.deck.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.swipedelete.core.designsystem.components.EmptyState
import com.swipedelete.core.model.MediaItem
import com.swipedelete.feature.deck.domain.Gesture

/**
 * The deck host (SPEC 5.1 / 5.2): the current card, its accessible action bar,
 * and two caller-owned slots around them.
 *
 * [topContent] is where the shell hangs panels that belong above the card — the
 * AC-19 whole-library affordance (narrowed at tier 0) is the first of these.
 * [bottomContent] is where the staging strip and history strip land (owned by
 * their own tasks). The host is presentation-only: it renders the item it is
 * given and hoists every decision, so the pure deck engine stays the single
 * source of truth.
 *
 * A decision that throws is a failed action, not a crash: the error [DeckHaptics]
 * pattern plays and the bar stays usable.
 */
@Composable
fun DeckScreen(
    item: MediaItem?,
    onDecision: (Gesture) -> Unit,
    onMove: () -> Unit,
    modifier: Modifier = Modifier,
    haptics: DeckHaptics = rememberDeckHaptics(),
    topContent: @Composable () -> Unit = {},
    bottomContent: @Composable () -> Unit = {},
) {
    val guardedDecision: (Gesture) -> Unit = { gesture ->
        runCatching { onDecision(gesture) }.onFailure { haptics.failure() }
    }
    val guardedMove: () -> Unit = {
        runCatching { onMove() }.onFailure { haptics.failure() }
    }

    Column(modifier = modifier.fillMaxSize()) {
        topContent()
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (item != null) {
                SwipeCard(
                    item = item,
                    onDecision = guardedDecision,
                    modifier = Modifier.fillMaxSize(),
                    haptics = haptics,
                    onRevealDock = guardedMove,
                )
            } else {
                EmptyState(modifier = Modifier.align(Alignment.Center))
            }
        }
        bottomContent()
        DeckActionBar(
            onDecision = guardedDecision,
            onMove = guardedMove,
            haptics = haptics,
        )
    }
}
