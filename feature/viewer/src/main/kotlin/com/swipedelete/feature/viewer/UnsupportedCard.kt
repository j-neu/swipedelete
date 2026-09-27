package com.swipedelete.feature.viewer

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.swipedelete.core.common.formatBytes
import com.swipedelete.core.designsystem.SwipeDeleteDimens
import com.swipedelete.core.designsystem.SwipeDeleteTheme
import com.swipedelete.core.designsystem.components.SwipeDeleteIcons
import com.swipedelete.core.model.MediaItem

/** Why an item cannot be rendered in-place, which picks the card's wording. */
enum class UnsupportedReason {
    /** A non-media document or an unknown type: metadata plus a hand-off. */
    DOCUMENT,

    /** Bytes that claim to be media but could not be decoded. */
    UNDECODABLE,
}

/**
 * SPEC 5.4: a document/other item gets a metadata card and an "Open with"
 * hand-off; an undecodable item gets an honest error naming the codec/MIME and
 * the same hand-off — **never a blank frame**. The MIME is always rendered
 * verbatim so the error names the actual format rather than guessing.
 */
@Composable
fun UnsupportedCard(
    item: MediaItem,
    reason: UnsupportedReason,
    onOpenWith: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = SwipeDeleteTheme.colors
    val mime = item.mime.ifBlank { "application/octet-stream" }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(SwipeDeleteDimens.SpacingMedium),
        verticalArrangement = Arrangement.spacedBy(
            space = SwipeDeleteDimens.SpacingSmall,
            alignment = Alignment.CenterVertically,
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = SwipeDeleteIcons.Empty,
            contentDescription = null,
            tint = colors.onSurfaceVariant,
        )
        Text(
            text = stringResource(
                when (reason) {
                    UnsupportedReason.DOCUMENT -> R.string.viewer_document_title
                    UnsupportedReason.UNDECODABLE -> R.string.viewer_unsupported_title
                },
            ),
            style = MaterialTheme.typography.titleMedium,
            color = colors.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(
                when (reason) {
                    UnsupportedReason.DOCUMENT -> R.string.viewer_document_body
                    UnsupportedReason.UNDECODABLE -> R.string.viewer_unsupported_body
                },
                mime,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Text(
            text = item.path ?: item.uri,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.viewer_unsupported_metadata_size, formatBytes(item.sizeBytes)),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
        val width = item.width
        val height = item.height
        if (width != null && height != null) {
            Text(
                text = stringResource(
                    R.string.viewer_unsupported_metadata_dimensions,
                    width,
                    height,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
        item.durationMs?.let { durationMs ->
            Text(
                text = stringResource(
                    R.string.viewer_unsupported_metadata_duration,
                    formatDuration(durationMs),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
        TextButton(
            onClick = { onOpenWith(item) },
            modifier = Modifier.defaultMinSize(
                minWidth = SwipeDeleteDimens.MinTouchTarget,
                minHeight = SwipeDeleteDimens.MinTouchTarget,
            ),
        ) {
            Text(text = stringResource(R.string.viewer_action_open_with))
        }
    }
}

/**
 * The "Open with" hand-off. Kept injectable so the card is testable without a
 * device and the app owns the intent-flags decision; the default launches an
 * `ACTION_VIEW` chooser with a read grant.
 */
@Composable
fun rememberOpenWithHandler(): (MediaItem) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { item -> launchOpenWith(context, item) }
    }
}

internal fun launchOpenWith(context: Context, item: MediaItem) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(mediaUri(item), item.mime.ifBlank { "*/*" })
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(Intent.createChooser(intent, null)) }
}
