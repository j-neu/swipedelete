package com.swipedelete.feature.viewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import com.swipedelete.core.model.MediaItem
import java.io.InputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A decoded bitmap together with the EXIF orientation that produced it. */
data class DisplayImage(
    val bitmap: Bitmap,
    val orientation: ImageOrientation,
) {
    /** The Compose-facing form; the bitmap is already oriented. */
    val imageBitmap: ImageBitmap get() = bitmap.asImageBitmap()
}

/**
 * Decodes an item's bytes for display, bounded by [target]. The `open` factory
 * is called more than once because orientation, bounds and pixels are three
 * separate reads of the same stream, and `StorageGateway.openStream` hands out
 * a fresh stream per call.
 *
 * A `null` result means the bytes could not be decoded; the caller renders the
 * honest "unsupported" path (Task 38) rather than a blank frame.
 */
fun interface ImageLoader {
    fun load(open: () -> InputStream, target: IntSize): DisplayImage?
}

/**
 * SPEC 9.5: the pager must never hold a full-resolution bitmap. [sampleSize] is
 * the power-of-two downsample factor that keeps the decoded bitmap at or above
 * the on-screen size — big enough to be crisp, small enough not to blow the
 * heap on a 108 MP photo.
 */
object ImageSizing {
    fun sampleSize(sourceWidth: Int, sourceHeight: Int, target: IntSize): Int {
        if (sourceWidth <= 0 || sourceHeight <= 0) return 1
        val targetWidth = target.width.coerceAtLeast(1)
        val targetHeight = target.height.coerceAtLeast(1)
        var sample = 1
        while (sourceWidth / (sample * 2) >= targetWidth && sourceHeight / (sample * 2) >= targetHeight) {
            sample *= 2
        }
        return sample
    }
}

/**
 * The framework decoder. Stills go through `BitmapFactory` rather than
 * `ImageDecoder` on purpose: `ImageDecoder` silently applies EXIF orientation
 * itself, so pairing it with the orientation this module reads and applies
 * would rotate a portrait photo twice. `BitmapFactory` leaves orientation to
 * the caller, which is the whole point of [ExifOrientation].
 */
object AndroidImageLoader : ImageLoader {

    override fun load(open: () -> InputStream, target: IntSize): DisplayImage? {
        val orientation = open().use { ExifOrientation.read(it) }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }

        val options = BitmapFactory.Options().apply {
            inSampleSize = ImageSizing.sampleSize(bounds.outWidth, bounds.outHeight, target)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = open().use { BitmapFactory.decodeStream(it, null, options) } ?: return null

        return DisplayImage(orient(decoded, orientation), orientation)
    }

    private fun orient(bitmap: Bitmap, orientation: ImageOrientation): Bitmap {
        if (orientation == ImageOrientation.NORMAL) return bitmap
        val matrix = Matrix()
        // postConcat semantics: the rotation is applied first, the mirror
        // second, which reproduces all eight EXIF orientations including the
        // transposed ones (5 and 7).
        matrix.postRotate(orientation.rotationDegrees.toFloat())
        if (orientation.mirrored) matrix.postScale(-1f, 1f)
        val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (oriented !== bitmap) bitmap.recycle()
        return oriented
    }
}

/**
 * The zoom/pan state the image viewer drives. Kept separate from the composable
 * so the pinch/double-tap arithmetic is testable without a gesture engine.
 */
@Stable
class ZoomState(
    val minScale: Float = 1f,
    val maxScale: Float = 4f,
    private val doubleTapScale: Float = 2f,
) {
    var scale: Float by mutableFloatStateOf(minScale)
        private set
    var offset: Offset by mutableStateOf(Offset.Zero)
        private set

    /** Double-tap toggles between fit and [doubleTapScale], the conventional behaviour. */
    fun doubleTap() {
        if (scale > minScale) {
            reset()
        } else {
            scale = doubleTapScale.coerceIn(minScale, maxScale)
            offset = Offset.Zero
        }
    }

    fun zoomBy(factor: Float) {
        scale = (scale * factor).coerceIn(minScale, maxScale)
        if (scale <= minScale) offset = Offset.Zero
    }

    /** Panning a fitted image would just slide it off-screen; only pan while zoomed. */
    fun panBy(delta: Offset) {
        if (scale > minScale) offset += delta
    }

    fun reset() {
        scale = minScale
        offset = Offset.Zero
    }
}

/**
 * SPEC 5.4: full-resolution image decode (bounded by the laid-out target),
 * pinch and double-tap zoom, pan, EXIF orientation respected.
 */
@Composable
fun ImageViewer(
    item: MediaItem,
    openStream: (MediaItem) -> InputStream,
    modifier: Modifier = Modifier,
    loader: ImageLoader = AndroidImageLoader,
    zoom: ZoomState = remember { ZoomState() },
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val target = IntSize(maxWidth.value.toInt(), maxHeight.value.toInt())
        var display by remember(item.id) { mutableStateOf<DisplayImage?>(null) }

        LaunchedEffect(item.id, target) {
            if (target.width > 0 && target.height > 0) {
                display = withContext(ioDispatcher) { loader.load({ openStream(item) }, target) }
            }
        }

        val image = display ?: return@BoxWithConstraints
        val contentDescription = stringResource(R.string.viewer_cd_image)
        val currentScale = zoom.scale
        val currentOffset = zoom.offset
        Image(
            bitmap = image.imageBitmap,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = currentScale
                    scaleY = currentScale
                    translationX = currentOffset.x
                    translationY = currentOffset.y
                }
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { zoom.doubleTap() })
                }
                .pointerInput(Unit) {
                    // A custom detector rather than detectTransformGestures:
                    // the stock detector consumes every drag, which would steal
                    // the viewer's swipe-down-to-dismiss even on a fitted image.
                    // Consume only a real pinch, or a pan while already zoomed.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        while (true) {
                            val event = awaitPointerEvent()
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            val consumes = zoomChange != 1f || (zoom.scale > zoom.minScale && panChange != Offset.Zero)
                            if (zoomChange != 1f) zoom.zoomBy(zoomChange)
                            if (consumes) {
                                zoom.panBy(panChange)
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                            if (event.changes.none { it.pressed }) break
                        }
                    }
                },
        )
    }
}
