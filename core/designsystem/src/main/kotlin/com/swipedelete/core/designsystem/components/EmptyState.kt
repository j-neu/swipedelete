package com.swipedelete.core.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.swipedelete.core.designsystem.R
import com.swipedelete.core.designsystem.SwipeDeleteDimens
import com.swipedelete.core.designsystem.SwipeDeleteTheme

/**
 * The honest empty state: it says what is missing in plain language. Text is
 * resolved from resources with no user-facing literals in the component.
 */
@Composable
fun EmptyState(
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.empty_state_default_title),
    body: String = stringResource(R.string.empty_state_default_body),
    iconContentDescription: String? = null,
) {
    val colors = SwipeDeleteTheme.colors
    Column(
        modifier = modifier.padding(SwipeDeleteDimens.SpacingMedium),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SwipeDeleteDimens.SpacingSmall),
    ) {
        Icon(
            imageVector = SwipeDeleteIcons.Empty,
            contentDescription = iconContentDescription,
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = colors.onSurface,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
    }
}
