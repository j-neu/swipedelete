package com.swipedelete.feature.settings.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.swipedelete.core.designsystem.SwipeDeleteDimens
import com.swipedelete.core.model.Slot
import com.swipedelete.core.model.SlotId
import com.swipedelete.feature.settings.R

/**
 * The "pick your five folders" control (SPEC 4.5).
 *
 * It is deliberately not a permission dialog: each row is a folder the user
 * chose, the add affordance disappears once [MAX_SLOTS] are set (and says so in
 * words), and every slot can be removed. All copy comes from resources.
 */
@Composable
fun GrantSlotPicker(
    slots: List<Slot>,
    onPickFolder: () -> Unit,
    onRemoveSlot: (SlotId) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SwipeDeleteDimens.SpacingSmall),
    ) {
        Text(
            text = stringResource(R.string.onboarding_slot_count, slots.size, MAX_SLOTS),
            style = MaterialTheme.typography.labelLarge,
        )

        slots.forEach { slot ->
            val removeLabel = stringResource(R.string.onboarding_slot_remove_named, slot.name)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = slot.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                )
                TextButton(
                    onClick = { onRemoveSlot(slot.id) },
                    modifier = Modifier
                        .semantics { contentDescription = removeLabel }
                        .defaultMinSize(
                            minWidth = SwipeDeleteDimens.MinTouchTarget,
                            minHeight = SwipeDeleteDimens.MinTouchTarget,
                        ),
                ) {
                    Text(text = stringResource(R.string.onboarding_slot_remove))
                }
            }
        }

        if (slots.size < MAX_SLOTS) {
            TextButton(
                onClick = onPickFolder,
                modifier = Modifier.defaultMinSize(
                    minWidth = SwipeDeleteDimens.MinTouchTarget,
                    minHeight = SwipeDeleteDimens.MinTouchTarget,
                ),
            ) {
                Text(text = stringResource(R.string.onboarding_slot_add))
            }
        } else {
            Text(
                text = stringResource(R.string.onboarding_slot_limit),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
