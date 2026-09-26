package com.swipedelete.feature.viewer

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.view.SurfaceView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import com.swipedelete.core.designsystem.SwipeDeleteDimens
import com.swipedelete.core.designsystem.SwipeDeleteTheme
import com.swipedelete.core.model.MediaItem
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToLong

/** The direction a frame step moves. */
enum class FrameStep(val direction: Int) {
    BACKWARD(-1),
    FORWARD(1),
}

/**
 * The narrow slice of player operations the video transport drives. Keeping it
 * this narrow is what lets [VideoControls] be pinned in a JVM unit test with no
 * device and no ExoPlayer instance.
 */
interface VideoPlayerOps {
    val isPlaying: Boolean
    val currentPositionMs: Long
    val durationMs: Long
    val playbackSpeed: Float
    val isLooping: Boolean

    /** One frame at the media's own rate, or a sane fallback when it is unknown. */
    val frameDurationMs: Long

    /** How many audio tracks can be selected; zero when none has been found yet. */
    val audioTrackCount: Int

    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setPlaybackSpeed(speed: Float)
    fun setLooping(looping: Boolean)
    fun selectAudioTrack(index: Int)
}

/**
 * SPEC 5.4 (video): the control arithmetic behind the transport. Every callback
 * maps to exactly one player operation, except a frame step which must pause
 * first so the single-frame move is observable.
 */
class VideoControls(private val player: VideoPlayerOps) {

    fun togglePlay() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(clampPosition(positionMs))
    }

    fun skip(deltaMs: Long) {
        seekTo(player.currentPositionMs + deltaMs)
    }

    fun skipForward() = skip(SKIP_MS)

    fun skipBackward() = skip(-SKIP_MS)

    fun setSpeed(speed: Float) {
        player.setPlaybackSpeed(clampSpeed(speed))
    }

    /** A frame step always pauses, then moves exactly one frame. */
    fun stepFrame(step: FrameStep) {
        player.pause()
        seekTo(player.currentPositionMs + player.frameDurationMs * step.direction)
    }

    fun toggleLoop() {
        player.setLooping(!player.isLooping)
    }

    fun selectAudioTrack(index: Int) {
        if (index in 0 until player.audioTrackCount) player.selectAudioTrack(index)
    }

    private fun clampPosition(positionMs: Long): Long {
        val duration = player.durationMs
        return if (duration > 0) positionMs.coerceIn(0L, duration) else positionMs.coerceAtLeast(0L)
    }

    companion object {
        const val MIN_SPEED = 0.25f
        const val MAX_SPEED = 3.0f
        const val SKIP_MS = 10_000L

        fun clampSpeed(speed: Float): Float = speed.coerceIn(MIN_SPEED, MAX_SPEED)
    }
}

/** Adapts a real [ExoPlayer] onto the tested [VideoPlayerOps] seam. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class ExoVideoPlayerOps(private val player: ExoPlayer) : VideoPlayerOps {

    override val isPlaying: Boolean get() = player.isPlaying
    override val currentPositionMs: Long get() = player.currentPosition.coerceAtLeast(0L)
    override val durationMs: Long get() = player.duration.coerceAtLeast(0L)
    override val playbackSpeed: Float get() = player.playbackParameters.speed
    override val isLooping: Boolean get() = player.repeatMode == Player.REPEAT_MODE_ONE

    override val frameDurationMs: Long
        get() {
            val rate = player.videoFormat?.frameRate ?: Format.NO_VALUE.toFloat()
            return if (rate > 0f) (MS_PER_SECOND / rate).roundToLong().coerceAtLeast(1L) else FALLBACK_FRAME_MS
        }

    override val audioTrackCount: Int
        get() = audioGroups(player.currentTracks).size

    override fun play() {
        player.play()
    }

    override fun pause() {
        player.pause()
    }

    override fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
    }

    override fun setPlaybackSpeed(speed: Float) {
        player.setPlaybackSpeed(speed)
    }

    override fun setLooping(looping: Boolean) {
        player.repeatMode = if (looping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    override fun selectAudioTrack(index: Int) {
        val group = audioGroups(player.currentTracks).getOrNull(index) ?: return
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
            .build()
    }

    private companion object {
        const val MS_PER_SECOND = 1000f

        /** ~30 fps, used only until the first video format is known. */
        const val FALLBACK_FRAME_MS = 33L
    }
}

private fun audioGroups(tracks: Tracks): List<Tracks.Group> =
    tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO && it.length > 0 }

/** The display label of each selectable audio track, null when the media names none. */
fun audioTrackLabels(tracks: Tracks): List<String?> =
    audioGroups(tracks).map { group ->
        val format = group.getTrackFormat(0)
        format.label ?: format.language?.takeUnless { it == "und" }
    }

/** The index of the currently selected audio track, or 0 when none is selected. */
fun selectedAudioTrackIndex(tracks: Tracks): Int {
    val index = audioGroups(tracks).indexOfFirst { it.isSelected }
    return if (index >= 0) index else 0
}

/** A frame grabbed for the scrub preview, or null when it cannot be decoded. */
fun interface VideoThumbnailLoader {
    fun frameAt(positionMs: Long): Bitmap?
}

/**
 * The framework thumbnail source. A retriever per call keeps the loader
 * stateless — scrubbing is on a background dispatcher, and a shared, released
 * retriever would be a race.
 */
class MediaMetadataThumbnailLoader(
    private val context: Context,
    private val item: MediaItem,
) : VideoThumbnailLoader {

    override fun frameAt(positionMs: Long): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, mediaUri(item))
            retriever.getFrameAtTime(
                positionMs.coerceAtLeast(0L) * MICROS_PER_MS,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            )
        } catch (_: RuntimeException) {
            null
        } catch (_: java.io.IOException) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private companion object {
        const val MICROS_PER_MS = 1_000L
    }
}

/**
 * SPEC 5.4 (video): a Media3 surface with play/pause, scrub with thumbnail
 * preview, ±10 s, speed 0.25×–3×, frame step, loop toggle and audio-track
 * selection. Audio is deliberately never disabled: the viewer is the place a
 * silent autoplay card could be judged, so the sound is available here.
 *
 * Exactly one player is created per item and is released on disposal (M1-T11
 * owns the memory ceiling, not this task).
 */
@Composable
fun VideoViewer(
    item: MediaItem,
    modifier: Modifier = Modifier,
    createPlayer: (Context) -> ExoPlayer = { context -> ExoPlayer.Builder(context).build() },
    thumbnailLoader: (Context, MediaItem) -> VideoThumbnailLoader = { context, media ->
        MediaMetadataThumbnailLoader(context, media)
    },
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    val context = LocalContext.current
    val player = remember(item.id) { createPlayer(context) }
    val controls = remember(player) { VideoControls(ExoVideoPlayerOps(player)) }
    val previews = remember(item.id, thumbnailLoader) { thumbnailLoader(context, item) }

    var playing by remember(item.id) { mutableStateOf(false) }
    var positionMs by remember(item.id) { mutableLongStateOf(0L) }
    var durationMs by remember(item.id) { mutableLongStateOf(item.durationMs ?: 0L) }
    var speed by remember(item.id) { mutableFloatStateOf(1f) }
    var looping by remember(item.id) { mutableStateOf(false) }
    var availableTracks by remember(item.id) { mutableStateOf(Tracks.EMPTY) }

    var scrubbing by remember(item.id) { mutableStateOf(false) }
    var scrubPositionMs by remember(item.id) { mutableLongStateOf(0L) }
    var previewFrame by remember(item.id) { mutableStateOf<Bitmap?>(null) }

    val surfaceView = remember(item.id) { SurfaceView(context) }

    DisposableEffect(player, surfaceView) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    durationMs = player.duration.coerceAtLeast(0L)
                }
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                speed = playbackParameters.speed
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                looping = repeatMode == Player.REPEAT_MODE_ONE
            }

            override fun onTracksChanged(tracks: Tracks) {
                availableTracks = tracks
            }
        }
        player.addListener(listener)
        player.setVideoSurfaceView(surfaceView)
        onDispose {
            player.removeListener(listener)
            player.clearVideoSurfaceView(surfaceView)
            player.release()
        }
    }

    LaunchedEffect(player, item.id) {
        playing = false
        positionMs = 0L
        durationMs = item.durationMs ?: 0L
        availableTracks = Tracks.EMPTY
        player.setMediaItem(toMedia3Item(item))
        player.prepare()
    }

    LaunchedEffect(playing) {
        while (playing) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            delay(POSITION_POLL_MS)
        }
    }

    LaunchedEffect(scrubbing, scrubPositionMs) {
        if (!scrubbing) {
            previewFrame = null
            return@LaunchedEffect
        }
        previewFrame = withContext(ioDispatcher) { previews.frameAt(scrubPositionMs) }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { surfaceView },
            modifier = Modifier.fillMaxSize(),
        )

        VideoTransport(
            playing = playing,
            positionMs = if (scrubbing) scrubPositionMs else positionMs,
            durationMs = durationMs,
            speed = speed,
            looping = looping,
            scrubbing = scrubbing,
            preview = previewFrame,
            audioTracks = audioTrackLabels(availableTracks),
            selectedAudioTrack = selectedAudioTrackIndex(availableTracks),
            onToggle = controls::togglePlay,
            onScrub = { position ->
                scrubPositionMs = position
                scrubbing = true
            },
            onScrubFinished = {
                controls.seekTo(scrubPositionMs)
                scrubbing = false
            },
            onSkipBackward = controls::skipBackward,
            onSkipForward = controls::skipForward,
            onFrameStep = controls::stepFrame,
            onSpeed = controls::setSpeed,
            onToggleLoop = controls::toggleLoop,
            onSelectAudioTrack = controls::selectAudioTrack,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

private const val POSITION_POLL_MS = 250L

/**
 * The video transport surface, split from the player so it can be rendered and
 * asserted without an ExoPlayer.
 */
@Composable
fun VideoTransport(
    playing: Boolean,
    positionMs: Long,
    durationMs: Long,
    speed: Float,
    looping: Boolean,
    scrubbing: Boolean,
    preview: Bitmap?,
    audioTracks: List<String?>,
    selectedAudioTrack: Int,
    onToggle: () -> Unit,
    onScrub: (Long) -> Unit,
    onScrubFinished: () -> Unit,
    onSkipBackward: () -> Unit,
    onSkipForward: () -> Unit,
    onFrameStep: (FrameStep) -> Unit,
    onSpeed: (Float) -> Unit,
    onToggleLoop: () -> Unit,
    onSelectAudioTrack: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(SwipeDeleteDimens.SpacingMedium),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (scrubbing && preview != null) {
            Image(
                bitmap = preview.asImageBitmap(),
                contentDescription = stringResource(R.string.viewer_video_preview),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = PREVIEW_MAX_HEIGHT_DP),
            )
        }

        Text(
            text = stringResource(
                R.string.viewer_audio_position_of_duration,
                formatDuration(positionMs),
                formatDuration(durationMs),
            ),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onSkipBackward) {
                Text(text = stringResource(R.string.viewer_video_skip_backward))
            }
            TextButton(onClick = { onFrameStep(FrameStep.BACKWARD) }) {
                Text(text = stringResource(R.string.viewer_video_frame_backward))
            }
            TextButton(onClick = onToggle) {
                Text(
                    text = stringResource(
                        if (playing) R.string.viewer_action_pause else R.string.viewer_action_play,
                    ),
                )
            }
            TextButton(onClick = { onFrameStep(FrameStep.FORWARD) }) {
                Text(text = stringResource(R.string.viewer_video_frame_forward))
            }
            TextButton(onClick = onSkipForward) {
                Text(text = stringResource(R.string.viewer_video_skip_forward))
            }
            TextButton(onClick = onToggleLoop) {
                Text(
                    text = stringResource(
                        if (looping) R.string.viewer_video_loop_on else R.string.viewer_video_loop,
                    ),
                )
            }
        }

        Slider(
            value = if (durationMs > 0) {
                (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
            } else {
                0f
            },
            onValueChange = { fraction -> onScrub((fraction * durationMs).toLong()) },
            onValueChangeFinished = onScrubFinished,
            enabled = durationMs > 0,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(SwipeDeleteDimens.SpacingSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SPEED_PRESETS.forEach { preset ->
                TextButton(onClick = { onSpeed(preset) }) {
                    Text(text = stringResource(R.string.viewer_video_speed_option, formatSpeed(preset)))
                }
            }
            if (audioTracks.size > 1) {
                audioTracks.forEachIndexed { index, label ->
                    TextButton(onClick = { onSelectAudioTrack(index) }) {
                        Text(
                            text = label
                                ?: stringResource(R.string.viewer_video_audio_track_n, index + 1),
                            color = if (index == selectedAudioTrack) {
                                SwipeDeleteTheme.colors.accent
                            } else {
                                SwipeDeleteTheme.colors.onSurface
                            },
                        )
                    }
                }
            }
        }
    }
}

private val PREVIEW_MAX_HEIGHT_DP = 180.dp

/** The speeds the transport offers, all inside [VideoControls.MIN_SPEED]–[VideoControls.MAX_SPEED]. */
val SPEED_PRESETS: List<Float> = listOf(0.25f, 0.5f, 1f, 1.5f, 2f, 3f)

/** `1` rather than `1.0`, `0.25` rather than `.25`. */
fun formatSpeed(speed: Float): String =
    if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString()

