package com.swipedelete.core.designsystem.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.swipedelete.core.designsystem.R
import com.swipedelete.core.designsystem.SwipeDeleteDimens
import com.swipedelete.core.designsystem.SwipeDeleteTheme

/**
 * A destructive action (SPEC 5.2 / 11.4).
 *
 * The destructive signal is carried by three channels at once: a destructive
 * colour, a trash icon, and the label text. Colour is never the sole carrier of
 * meaning, and the component is a real button (click action, >= 48 dp) so the
 * gesture it mirrors always has a button equivalent.
 */
@Composable
fun DestructiveAction(
    text: String = stringResource(R.string.action_delete),
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    iconContentDescription: String = stringResource(R.string.cd_destructive),
) {
    requireIconContentDescription(iconContentDescription)
    val colors = SwipeDeleteTheme.colors
    Button(
        onClick = onClick,
        modifier = modifier.defaultMinSize(
            minWidth = SwipeDeleteDimens.MinTouchTarget,
            minHeight = SwipeDeleteDimens.MinTouchTarget,
        ),
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.destructive,
            contentColor = colors.onDestructive,
            disabledContainerColor = colors.destructive.copy(alpha = 0.38f),
            disabledContentColor = colors.onDestructive.copy(alpha = 0.38f),
        ),
    ) {
        Icon(
            imageVector = SwipeDeleteIcons.Destructive,
            contentDescription = iconContentDescription,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(SwipeDeleteDimens.SpacingSmall))
        Text(text = text)
    }
}

/**
 * Guards the icon half of the icon+text+colour contract. A blank content
 * description would silently demote the icon to decorative, leaving colour as
 * the sole destructive signal (SPEC 11.4), so it is rejected loudly.
 */
internal fun requireIconContentDescription(iconContentDescription: String) {
    require(iconContentDescription.isNotBlank()) {
        "DestructiveAction requires a non-empty iconContentDescription: the icon must not be " +
            "demoted to decorative, or colour would become the sole carrier of meaning (SPEC 11.4)"
    }
}
