package com.swipedelete.feature.viewer

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.annotation.RequiresApi
import android.widget.ImageView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.swipedelete.core.designsystem.SwipeDeleteDimens
import com.swipedelete.core.model.MediaItem
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The platform's looping-animation transport. Wrapping it keeps [GifViewer]
 * testable and keeps the viewer honest about API support: an animated drawable
 * exists only on API 28+, and below that the caller falls back to the still
 * image viewer rather than pretending.
 */
interface LoopingAnimation {
    fun start()
    fun stop()
    val isRunning: Boolean
}

/** A decoded animated image: the drawable to display plus its transport. */
class LoadedAnimation(
    val drawable: Drawable,
    val animation: LoopingAnimation,
)

/** Decodes animated bytes, or returns null when the bytes are not animated. */
fun interface GifLoader {
    fun load(open: () -> InputStream): LoadedAnimation?
}

/**
 * The framework loader. `ImageDecoder` handles animated GIF and animated WebP
 * on API 28+ and returns an [AnimatedImageDrawable]; earlier releases return
 * null and `ViewerScreen` renders the still image instead (graceful degradation,
 * AC-19). The bytes are buffered because `ImageDecoder` has no stream source —
 * an animated image has to be materialised to be decoded.
 */
object AndroidGifLoader : GifLoader {

    override fun load(open: () -> InputStream): LoadedAnimation? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return try {
            val bytes = open().use { it.readFully() }
            if (bytes.isEmpty()) return null
            val decoded = ImageDecoder.decodeDrawable(
                ImageDecoder.createSource(ByteBuffer.wrap(bytes)),
            )
            if (decoded is AnimatedImageDrawable) {
                decoded.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                LoadedAnimation(decoded, AnimatedImageDrawableTransport(decoded))
            } else {
                null
            }
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    /**
     * Drains the stream without `InputStream.readAllBytes`, which is API 33+.
     */
    private fun InputStream.readFully(): ByteArray {
        val out = ByteArrayOutputStream()
        copyTo(out)
        return out.toByteArray()
    }
}

/**
 * Only ever instantiated from [AndroidGifLoader] on API 28+, where
 * `AnimatedImageDrawable` exists; the annotation tells lint what the guard in
 * `load` already guarantees.
 */
@RequiresApi(Build.VERSION_CODES.P)
private class AnimatedImageDrawableTransport(
    private val drawable: AnimatedImageDrawable,
) : LoopingAnimation {
    override fun start() = drawable.start()
    override fun stop() = drawable.stop()
    override val isRunning: Boolean get() = drawable.isRunning
}

/**
 * SPEC 5.4: an animated GIF/WebP plays and loops, with play/pause. Playback
 * begins on open; the transport is a real toggle. The drawable is rendered
 * through an [ImageView] so its own frame-invalidation drives the animation.
 *
 * [fallback] renders when the bytes turn out not to be animated — a still WebP,
 * or an animated image on a release below API 28 — so the viewer degrades to
 * the still image instead of an empty frame.
 */
@Composable
fun GifViewer(
    item: MediaItem,
    openStream: (MediaItem) -> InputStream,
    modifier: Modifier = Modifier,
    loader: GifLoader = AndroidGifLoader,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    fallback: @Composable () -> Unit = {},
) {
    var loaded by remember(item.id) { mutableStateOf<LoadedAnimation?>(null) }
    var loadAttempted by remember(item.id) { mutableStateOf(false) }
    var playing by remember(item.id) { mutableStateOf(true) }

    LaunchedEffect(item.id) {
        playing = true
        loadAttempted = false
        loaded = withContext(ioDispatcher) { loader.load { openStream(item) } }
        loadAttempted = true
    }

    val animation = loaded
    if (animation == null) {
        if (loadAttempted) fallback()
        return
    }
    LaunchedEffect(animation, playing) {
        if (playing) animation.animation.start() else animation.animation.stop()
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { context ->
                ImageView(context).apply {
                    setImageDrawable(animation.drawable)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        TextButton(
            onClick = { playing = !playing },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(SwipeDeleteDimens.SpacingMedium),
        ) {
            Text(
                text = stringResource(
                    if (playing) R.string.viewer_action_pause else R.string.viewer_action_play,
                ),
            )
        }
    }
}
