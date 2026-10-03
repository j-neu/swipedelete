package com.swipedelete.core.designsystem.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.swipedelete.core.designsystem.R
import com.swipedelete.core.designsystem.SwipeDeleteDimens
import com.swipedelete.core.designsystem.SwipeDeleteTheme

/** One destination slot in the five-slot dock (SPEC 5.3). */
@Immutable
data class SlotSpec(
    val name: String,
    val enabled: Boolean = true,
    /**
     * The reason a disabled slot cannot receive a file (SPEC 5.2: "a slot greys
     * out if its destination is unavailable … with the reason on tap"). It is
     * localised by the caller, so the shared component carries no string of its
     * own for it.
     */
    val unavailableReason: String? = null,
)

/**
 * The shared five-slot dock (SPEC 5.2 / 11.4). :feature:deck consumes this.
 *
 * Each slot exposes a **non-drag** "Move" button: the dock is reachable by
 * tapping Move instead of dragging, so the drag gesture is never the only path.
 * The component is presentation-only — it reports the chosen slot and performs
 * no move itself.
 *
 * A disabled slot is greyed by the button's disabled state and, when
 * [onSlotUnavailable] is supplied, stays tappable as a whole so the caller can
 * surface [SlotSpec.unavailableReason] instead of leaving a dead end (AC-19).
 */
@Composable
fun SlotDock(
    slots: List<SlotSpec>,
    onSlotMove: (SlotSpec) -> Unit,
    modifier: Modifier = Modifier,
    onSlotUnavailable: ((SlotSpec) -> Unit)? = null,
) {
    val moveLabel = stringResource(R.string.action_move)
    val colors = SwipeDeleteTheme.colors
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = colors.surfaceVariant,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SwipeDeleteDimens.SpacingSmall),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            slots.forEach { slot ->
                val moveToLabel = stringResource(R.string.slot_move_to, slot.name)
                val unavailable = !slot.enabled && onSlotUnavailable != null
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = slot.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                        modifier = if (unavailable) {
                            Modifier
                                .clickable { onSlotUnavailable.invoke(slot) }
                                .semantics {
                                    contentDescription = slot.unavailableReason ?: moveToLabel
                                }
                        } else {
                            Modifier
                        },
                    )
                    TextButton(
                        onClick = { onSlotMove(slot) },
                        enabled = slot.enabled,
                        modifier = Modifier
                            .defaultMinSize(
                                minWidth = SwipeDeleteDimens.MinTouchTarget,
                                minHeight = SwipeDeleteDimens.MinTouchTarget,
                            )
                            .semantics { contentDescription = moveToLabel },
                    ) {
                        Icon(
                            imageVector = SwipeDeleteIcons.Move,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(SwipeDeleteDimens.SpacingSmall))
                        Text(text = moveLabel)
                    }
                }
            }
        }
    }
}
