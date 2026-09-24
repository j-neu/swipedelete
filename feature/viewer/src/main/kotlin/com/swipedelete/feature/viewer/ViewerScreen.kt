package com.swipedelete.feature.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import com.swipedelete.core.designsystem.SwipeDeleteDimens
import com.swipedelete.core.designsystem.SwipeDeleteTheme
import com.swipedelete.core.model.ItemId
import com.swipedelete.core.model.MediaItem
import java.io.InputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * The deck items the viewer pages through, plus where the caller asked to open.
 * The pager is horizontal (previous/next item); vertical down is dismissal
 * (SPEC 5.4, decision 5).
 */
data class ViewerPager(
    val items: List<MediaItem>,
    val startIndex: Int = 0,
) {
    /** The page to open on: the requested item if present, else the coerced start index. */
    fun initialIndex(itemId: ItemId): Int {
        if (items.isEmpty()) return 0
        val requested = items.indexOfFirst { it.id == itemId }
        if (requested >= 0) return requested
        return startIndex.coerceIn(0, items.lastIndex)
    }
}

/**
 * Decision 5: inside the viewer a downwards swipe **dismisses**. The dock is
 * deliberately not reachable by dragging, so a drag-down can mean exactly one
 * thing.
 */
object ViewerDismiss {

    /** Past this fraction of the viewer height, a slow drag dismisses. */
    const val DISTANCE_FRACTION = 0.25f

    /** A downward flick at or above this speed dismisses even if it is short. */
    const val VELOCITY_THRESHOLD_PX_PER_SECOND = 1000f

    fun shouldDismiss(
        dragDistancePx: Float,
        velocityPxPerSecond: Float,
        viewerHeightPx: Float,
    ): Boolean {
        if (dragDistancePx <= 0f) return false
        if (viewerHeightPx > 0f && dragDistancePx > viewerHeightPx * DISTANCE_FRACTION) return true
        return velocityPxPerSecond > VELOCITY_THRESHOLD_PX_PER_SECOND
    }
}

/**
 * SPEC 5.4: the viewer. Horizontal paging between deck items, swipe-down
 * dismissal, and a **button** for the slot dock (decision 5). Content is
 * dispatched by MIME: still images, animated images and audio are in this
 * task's scope; video (Task 37) and APK/unsupported (Task 38) are not.
 */
@Composable
fun ViewerScreen(
    itemId: ItemId,
    pager: ViewerPager,
    openStream: (MediaItem) -> InputStream,
    onDismiss: () -> Unit,
    onOpenDock: () -> Unit,
    modifier: Modifier = Modifier,
    imageLoader: ImageLoader = AndroidImageLoader,
    gifLoader: GifLoader = AndroidGifLoader,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    if (pager.items.isEmpty()) return

    val colors = SwipeDeleteTheme.colors
    val pagerState = rememberPagerState(initialPage = pager.initialIndex(itemId)) { pager.items.size }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(colors.surface),
    ) {
        val viewerHeightPx = constraints.maxHeight.toFloat()
        var dragDistance by remember { mutableFloatStateOf(0f) }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = dragDistance }
                .draggable(
                    state = rememberDraggableState { delta -> dragDistance += delta },
                    orientation = Orientation.Vertical,
                    onDragStopped = { velocity ->
                        val dismiss = ViewerDismiss.shouldDismiss(dragDistance, velocity, viewerHeightPx)
                        dragDistance = 0f
                        if (dismiss) onDismiss()
                    },
                ),
        ) { page ->
            ViewerContent(
                item = pager.items[page],
                openStream = openStream,
                imageLoader = imageLoader,
                gifLoader = gifLoader,
                ioDispatcher = ioDispatcher,
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(SwipeDeleteDimens.SpacingSmall),
        ) {
            TextButton(onClick = onOpenDock) {
                Text(text = stringResource(R.string.viewer_action_open_dock))
            }
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.viewer_cd_dismiss))
            }
        }
    }
}

@Composable
private fun ViewerContent(
    item: MediaItem,
    openStream: (MediaItem) -> InputStream,
    imageLoader: ImageLoader,
    gifLoader: GifLoader,
    ioDispatcher: CoroutineDispatcher,
) {
    when {
        item.mime.startsWith("audio/") -> AudioViewer(item)

        item.mime == "image/gif" || item.mime == "image/webp" -> GifViewer(
            item = item,
            openStream = openStream,
            loader = gifLoader,
            ioDispatcher = ioDispatcher,
            fallback = {
                ImageViewer(
                    item = item,
                    openStream = openStream,
                    loader = imageLoader,
                    ioDispatcher = ioDispatcher,
                )
            },
        )

        else -> ImageViewer(
            item = item,
            openStream = openStream,
            loader = imageLoader,
            ioDispatcher = ioDispatcher,
        )
    }
}
