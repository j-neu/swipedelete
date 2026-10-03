package com.swipedelete.feature.deck.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.swipedelete.core.designsystem.SwipeDeleteDimens
import com.swipedelete.core.designsystem.SwipeDeleteTheme
import com.swipedelete.core.designsystem.components.DestructiveAction
import com.swipedelete.core.designsystem.components.SwipeDeleteIcons
import com.swipedelete.feature.deck.R
import com.swipedelete.feature.deck.domain.Gesture

/** The test tag the action bar is addressed by in UI tests. */
const val DeckActionBarTestTag: String = "deck_action_bar"

/**
 * The three haptic patterns the deck surface speaks (SPEC 5.2): a light tick
 * when a drag crosses the commit threshold, a distinct pattern when a decision
 * drops, and an error pattern when an action fails.
 *
 * Kept as a seam rather than calling the platform directly so the patterns are
 * injectable — tests observe them, and the surface never has to guess whether a
 * device can vibrate.
 */
interface DeckHaptics {
    fun threshold()
    fun drop()
    fun failure()
}

/** No-op haptics: the default in tests and previews. */
object NoOpDeckHaptics : DeckHaptics {
    override fun threshold() = Unit
    override fun drop() = Unit
    override fun failure() = Unit
}

/** The device-backed haptics: a distinct platform constant per pattern. */
@Composable
fun rememberDeckHaptics(): DeckHaptics {
    val view = LocalView.current
    return remember(view) { ViewDeckHaptics(view) }
}

private class ViewDeckHaptics(private val view: View) : DeckHaptics {

    /** A light keyboard-like tick, the mildest pattern available on every API. */
    override fun threshold() {
        perform(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    /** The distinct drop pattern, stronger than [threshold]. */
    override fun drop() {
        perform(HapticFeedbackConstants.LONG_PRESS)
    }

    /** The error pattern; REJECT exists from API 30, LONG_PRESS is the fallback. */
    override fun failure() {
        perform(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                HapticFeedbackConstants.REJECT
            } else {
                HapticFeedbackConstants.LONG_PRESS
            },
        )
    }

    private fun perform(constant: Int) {
        view.performHapticFeedback(constant)
    }
}

/**
 * The bottom action bar (SPEC 5.2 / 11.4): the non-gesture equivalent of every
 * swipe. Delete mirrors swipe-left, Keep mirrors swipe-right, Skip mirrors
 * swipe-up, and Move opens the slot dock without a drag (mirroring swipe-down).
 *
 * Delete is rendered through [DestructiveAction], so it is a real >= 48 dp
 * button carrying colour **and** icon **and** text — never colour alone. The bar
 * also exposes the four decisions as custom accessibility actions, so TalkBack
 * can reach every one even where a button is hard to hit.
 *
 * Presentation-only: the caller owns the deck state and the dock.
 */
@Composable
fun DeckActionBar(
    onDecision: (Gesture) -> Unit,
    onMove: () -> Unit,
    modifier: Modifier = Modifier,
    haptics: DeckHaptics = rememberDeckHaptics(),
) {
    val colors = SwipeDeleteTheme.colors
    val barLabel = stringResource(R.string.deck_action_bar_cd)
    val deleteLabel = stringResource(R.string.deck_action_delete)
    val keepLabel = stringResource(R.string.deck_action_keep)
    val moveLabel = stringResource(R.string.deck_action_move)
    val skipLabel = stringResource(R.string.deck_action_skip)
    val keepCd = stringResource(R.string.deck_action_keep_cd)
    val moveCd = stringResource(R.string.deck_action_move_cd)
    val skipCd = stringResource(R.string.deck_action_skip_cd)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag(DeckActionBarTestTag)
            .semantics {
                contentDescription = barLabel
                customActions = listOf(
                    CustomAccessibilityAction(deleteLabel) {
                        haptics.drop(); onDecision(Gesture.DELETE); true
                    },
                    CustomAccessibilityAction(keepLabel) {
                        haptics.drop(); onDecision(Gesture.KEEP); true
                    },
                    CustomAccessibilityAction(moveLabel) {
                        haptics.drop(); onMove(); true
                    },
                    CustomAccessibilityAction(skipLabel) {
                        haptics.drop(); onDecision(Gesture.DEFER); true
                    },
                )
            },
        color = colors.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = SwipeDeleteDimens.SpacingSmall,
                    vertical = SwipeDeleteDimens.SpacingSmall,
                ),
            horizontalArrangement = Arrangement.spacedBy(SwipeDeleteDimens.SpacingSmall),
        ) {
            val minTarget = Modifier.defaultMinSize(
                minWidth = SwipeDeleteDimens.MinTouchTarget,
                minHeight = SwipeDeleteDimens.MinTouchTarget,
            )

            DestructiveAction(
                text = deleteLabel,
                onClick = {
                    haptics.drop()
                    onDecision(Gesture.DELETE)
                },
                modifier = Modifier.weight(1f).then(minTarget),
                iconContentDescription = stringResource(R.string.deck_action_delete_cd),
            )
            Button(
                onClick = {
                    haptics.drop()
                    onDecision(Gesture.KEEP)
                },
                modifier = Modifier
                    .weight(1f)
                    .then(minTarget)
                    .semantics { contentDescription = keepCd },
            ) {
                Text(text = keepLabel)
            }
            Button(
                onClick = {
                    haptics.drop()
                    onMove()
                },
                modifier = Modifier
                    .weight(1f)
                    .then(minTarget)
                    .semantics { contentDescription = moveCd },
            ) {
                Icon(
                    imageVector = SwipeDeleteIcons.Move,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(SwipeDeleteDimens.SpacingSmall))
                Text(text = moveLabel)
            }
            Button(
                onClick = {
                    haptics.drop()
                    onDecision(Gesture.DEFER)
                },
                modifier = Modifier
                    .weight(1f)
                    .then(minTarget)
                    .semantics { contentDescription = skipCd },
            ) {
                Text(text = skipLabel)
            }
        }
    }
}
