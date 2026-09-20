package com.swipedelete.feature.settings.grants

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.swipedelete.core.model.SourceId
import com.swipedelete.core.model.SourceRef
import com.swipedelete.feature.settings.R

/**
 * One persisted grant (SPEC 4.5): its display name, whether it can write, and a
 * **one-tap revoke**. The revoke affordance is a real `TextButton` with an
 * explicit content description, so it is exposed to accessibility services and
 * to test semantics as a clickable action — not a decorative icon.
 */
@Composable
fun GrantRow(
    source: SourceRef,
    onRevoke: (SourceId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val revokeLabel = stringResource(R.string.grants_revoke_named, source.displayName)
    val accessLabel = stringResource(
        if (source.access == SourceRef.Access.RW) {
            R.string.grants_access_write
        } else {
            R.string.grants_access_read
        },
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = SwipeDeleteDimens.SpacingSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = source.displayName, style = MaterialTheme.typography.bodyLarge)
            Text(text = accessLabel, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(
            onClick = { onRevoke(source.id) },
            modifier = Modifier
                .semantics { contentDescription = revokeLabel }
                .defaultMinSize(
                    minWidth = SwipeDeleteDimens.MinTouchTarget,
                    minHeight = SwipeDeleteDimens.MinTouchTarget,
                ),
        ) {
            Text(text = stringResource(R.string.grants_revoke_label))
        }
    }
}
