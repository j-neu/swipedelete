package com.swipedelete.feature.deck.ui

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.swipedelete.core.designsystem.SwipeDeleteDimens
import com.swipedelete.core.designsystem.SwipeDeleteTheme
import com.swipedelete.core.designsystem.components.SwipeDeleteIcons
import com.swipedelete.core.model.MediaItem
import com.swipedelete.feature.deck.R
import com.swipedelete.feature.deck.domain.Gesture
import kotlin.math.abs
import kotlin.math.roundToInt

/** The test tag the swipe surface is addressed by in UI tests. */
const val SwipeCardTestTag: String = "swipe_card"

/** The four drag directions a card can commit to. */
enum class SwipeDirection { LEFT, RIGHT, UP, DOWN }

/**
 * The threshold/velocity arithmetic of the swipe surface (SPEC 5.2).
 *
 * A direction commits when the drag passes [commitDistancePx], **or** when it is
 * at least [flingDistancePx] and its release velocity passes
 * [minFlingVelocityPxPerSec] — a fast short flick commits at the lower fling
 * distance. Anything else returns `null` and the card snaps back. The direction
 * is the dominant axis of the drag.
 *
 * Kept as a pure object so the px boundaries are proven at their exact edges in
 * unit tests rather than only through timing-sensitive injected gestures.
 */
object SwipeResolver {

    fun resolve(
        drag: Offset,
        velocity: Offset,
        commitDistancePx: Float,
        flingDistancePx: Float,
        minFlingVelocityPxPerSec: Float,
    ): SwipeDirection? {
        val distance = drag.getDistance()
        val flung = velocity.getDistance() >= minFlingVelocityPxPerSec && distance >= flingDistancePx
        if (distance < commitDistancePx && !flung) return null
        return directionOf(drag)
    }

    private fun directionOf(drag: Offset): SwipeDirection? = when {
        drag == Offset.Zero -> null
        abs(drag.x) >= abs(drag.y) -> if (drag.x < 0f) SwipeDirection.LEFT else SwipeDirection.RIGHT
        else -> if (drag.y < 0f) SwipeDirection.UP else SwipeDirection.DOWN
    }
}

private val CommitDistance = 96.dp
private val FlingDistance = 48.dp
private val MinFlingVelocity = 800.dp

/**
 * A single deck card (SPEC 5.2). Dragging it commits a [Gesture] once the drag
 * passes the commit threshold or is a fast enough flick; a drag that does not
 * commit moves nothing.
 *
 * The destructive direction is signalled by three channels at once — the
 * destructive colour, the trash icon and the "Delete" text (SPEC 11.4) — and
 * every direction also exists as a custom accessibility action, so the gesture
 * always has a non-gesture equivalent (AC-19).
 *
 * The thumbnail is decoded at a bounded target size through [ImageDecoder] on
 * API 28+ (and [BitmapFactory] below it); a thumbnail that cannot be produced
 * degrades to the surface token rather than crashing.
 */
@Composable
fun SwipeCard(
    item: MediaItem,
    onDecision: (Gesture) -> Unit,
    modifier: Modifier = Modifier,
    haptics: DeckHaptics = rememberDeckHaptics(),
    /**
     * A drag-down commits nothing but reveals the slot dock (SPEC 5.2); the same
     * reveal is exposed as the "Move" custom action so it is never drag-only
     * (AC-19).
     */
    onRevealDock: () -> Unit = {},
) {
    val colors = SwipeDeleteTheme.colors
    val density = LocalDensity.current
    val commitDistancePx = with(density) { CommitDistance.toPx() }
    val flingDistancePx = with(density) { FlingDistance.toPx() }
    val minFlingVelocityPxPerSec = with(density) { MinFlingVelocity.toPx() }

    var dragOffset by remember(item.id) { mutableStateOf(Offset.Zero) }
    val velocityTracker = remember(item.id) { VelocityTracker() }

    val deleteLabel = stringResource(R.string.swipe_delete)
    val keepLabel = stringResource(R.string.swipe_keep)
    val deferLabel = stringResource(R.string.swipe_defer)
    val moveLabel = stringResource(R.string.swipe_move)

    val liveDirection = SwipeResolver.resolve(
        drag = dragOffset,
        velocity = Offset.Zero,
        commitDistancePx = commitDistancePx,
        flingDistancePx = flingDistancePx,
        minFlingVelocityPxPerSec = minFlingVelocityPxPerSec,
    )

    // One light tick the first time a drag crosses the commit threshold; reset
    // when the card returns to its resting position (SPEC 5.2 haptics).
    var thresholdFired by remember(item.id) { mutableStateOf(false) }
    LaunchedEffect(liveDirection) {
        if (liveDirection != null && !thresholdFired) {
            haptics.threshold()
            thresholdFired = true
        } else if (liveDirection == null) {
            thresholdFired = false
        }
    }

    val containerColor = when (liveDirection) {
        SwipeDirection.LEFT -> colors.destructiveContainer
        SwipeDirection.RIGHT -> colors.accent.copy(alpha = 0.12f)
        SwipeDirection.UP -> colors.surfaceVariant
        SwipeDirection.DOWN, null -> colors.surface
    }

    Surface(
        modifier = modifier
            .offset { IntOffset(dragOffset.x.roundToInt(), dragOffset.y.roundToInt()) }
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(deleteLabel) { onDecision(Gesture.DELETE); true },
                    CustomAccessibilityAction(keepLabel) { onDecision(Gesture.KEEP); true },
                    CustomAccessibilityAction(deferLabel) { onDecision(Gesture.DEFER); true },
                    CustomAccessibilityAction(moveLabel) { onRevealDock(); true },
                )
            }
            .pointerInput(item.id) {
                detectDragGestures(
                    onDragStart = { velocityTracker.resetTracking() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        dragOffset += dragAmount
                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                    },
                    onDragEnd = {
                        val velocity = velocityTracker.calculateVelocity()
                        val direction = SwipeResolver.resolve(
                            drag = dragOffset,
                            velocity = Offset(velocity.x, velocity.y),
                            commitDistancePx = commitDistancePx,
                            flingDistancePx = flingDistancePx,
                            minFlingVelocityPxPerSec = minFlingVelocityPxPerSec,
                        )
                        dragOffset = Offset.Zero
                        if (direction != null) haptics.drop()
                        when (direction) {
                            SwipeDirection.LEFT -> onDecision(Gesture.DELETE)
                            SwipeDirection.RIGHT -> onDecision(Gesture.KEEP)
                            SwipeDirection.UP -> onDecision(Gesture.DEFER)
                            SwipeDirection.DOWN -> onRevealDock()
                            null -> Unit
                        }
                    },
                    onDragCancel = { dragOffset = Offset.Zero },
                )
            },
        color = containerColor,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Thumbnail(item = item, modifier = Modifier.fillMaxSize())
            SwipeDirectionOverlay(
                direction = liveDirection,
                deleteLabel = deleteLabel,
                keepLabel = keepLabel,
                deferLabel = deferLabel,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

@Composable
private fun SwipeDirectionOverlay(
    direction: SwipeDirection?,
    deleteLabel: String,
    keepLabel: String,
    deferLabel: String,
    modifier: Modifier = Modifier,
) {
    val colors = SwipeDeleteTheme.colors
    val label = when (direction) {
        SwipeDirection.LEFT -> deleteLabel
        SwipeDirection.RIGHT -> keepLabel
        SwipeDirection.UP -> deferLabel
        SwipeDirection.DOWN, null -> null
    } ?: return

    val container = when (direction) {
        SwipeDirection.LEFT -> colors.destructive
        else -> colors.surfaceVariant
    }
    val content = when (direction) {
        SwipeDirection.LEFT -> colors.onDestructive
        else -> colors.onSurfaceVariant
    }

    Surface(modifier = modifier, color = container, contentColor = content) {
        Row(
            modifier = Modifier.padding(SwipeDeleteDimens.SpacingMedium),
            horizontalArrangement = Arrangement.spacedBy(SwipeDeleteDimens.SpacingSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (direction == SwipeDirection.LEFT) {
                Icon(
                    imageVector = SwipeDeleteIcons.Destructive,
                    contentDescription = deleteLabel,
                    modifier = Modifier.size(24.dp),
                )
            }
            Text(text = label, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/**
 * A bounded thumbnail for [item], falling back to the surface token when the
 * item cannot be decoded. Shared with the staging preview grid so the deck has
 * a single thumbnail path (D7: no Coil).
 */
@Composable
internal fun Thumbnail(item: MediaItem, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, item.uri) {
        value = runCatching { decodeThumbnail(context.contentResolver, item.uri) }.getOrNull()
    }
    val decoded = bitmap
    if (decoded != null) {
        Image(
            bitmap = decoded,
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    } else {
        Surface(modifier = modifier, color = SwipeDeleteTheme.colors.surfaceVariant) {}
    }
}

/** Decodes a bounded thumbnail; returns null when the item cannot be decoded. */
private fun decodeThumbnail(resolver: ContentResolver, uri: String): ImageBitmap? {
    val parsed = Uri.parse(uri)
    val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val source = ImageDecoder.createSource(resolver, parsed)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val maxDimension = 512
            val width = info.size.width
            val height = info.size.height
            val longest = maxOf(width, height)
            if (longest > maxDimension) {
                val scale = maxDimension.toFloat() / longest
                decoder.setTargetSize(
                    (width * scale).roundToInt().coerceAtLeast(1),
                    (height * scale).roundToInt().coerceAtLeast(1),
                )
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } else {
        resolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it) }
    }
    return bitmap?.asImageBitmap()
}
