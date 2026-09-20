package com.swipedelete.core.designsystem.components

import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.swipedelete.core.designsystem.R
import com.swipedelete.core.designsystem.SwipeDeleteDimens
import com.swipedelete.core.designsystem.SwipeDeleteTheme

/**
 * The **non-destructive** primary action, honouring the 48 dp touch-target
 * floor.
 *
 * Do not use this for deletion, commit or any other destructive path: it has no
 * icon and carries the accent (not destructive) colour, so it cannot satisfy
 * SPEC 11.4's icon+text+colour rule. Destructive actions must go through
 * [DestructiveAction] (or a component that renders it), which is the only
 * affordance in this module that structurally guarantees all three signals.
 */
@Composable
fun PrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    text: String = stringResource(R.string.action_continue),
    enabled: Boolean = true,
) {
    val colors = SwipeDeleteTheme.colors
    Button(
        onClick = onClick,
        modifier = modifier.defaultMinSize(
            minWidth = SwipeDeleteDimens.MinTouchTarget,
            minHeight = SwipeDeleteDimens.MinTouchTarget,
        ),
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.accent,
            contentColor = colors.onAccent,
            disabledContainerColor = colors.accent.copy(alpha = 0.38f),
            disabledContentColor = colors.onAccent.copy(alpha = 0.38f),
        ),
    ) {
        Text(text = text)
    }
}
