package com.swipedelete.feature.viewer

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.swipedelete.core.designsystem.SwipeDeleteDimens
import com.swipedelete.core.model.MediaItem
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.delay

/**
 * SPEC 5.4: audio transport controls and a duration.
 *
 * The player needs a seekable data source of its own, not a caller-supplied
 * `InputStream`: feeding a stream would mean buffering the whole file and
 * giving up seeking, which is the opposite of a transport. When [streamFactory]
 * is supplied (the shipped path), audio is played through a gateway-backed
 * [GatewayDataSource] so SAF items work; otherwise it falls back to the item's
 * own URI.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
fun AudioViewer(
    item: MediaItem,
    modifier: Modifier = Modifier,
    streamFactory: ((MediaItem) -> InputStream)? = null,
) {
    val context = LocalContext.current
    val player = remember(item.id) { ExoPlayer.Builder(context).build() }

    var playing by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    durationMs = player.duration.coerceAtLeast(0L)
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(player, item.id, streamFactory) {
        playing = false
        positionMs = 0L
        durationMs = item.durationMs ?: 0L
        if (streamFactory != null) {
            val source = ProgressiveMediaSource.Factory(GatewayDataSourceFactory(item, streamFactory))
                .createMediaSource(toMedia3Item(item))
            player.setMediaSource(source)
        } else {
            player.setMediaItem(toMedia3Item(item))
        }
        player.prepare()
    }

    LaunchedEffect(playing) {
        while (playing) {
            positionMs = player.currentPosition
            delay(POSITION_POLL_MS)
        }
    }

    AudioTransport(
        playing = playing,
        positionMs = positionMs,
        durationMs = durationMs,
        onToggle = {
            if (player.isPlaying) player.pause() else player.play()
        },
        onSeek = { player.seekTo(it) },
        modifier = modifier,
    )
}

private const val POSITION_POLL_MS = 250L

/** The transport surface, split from the player so it can be tested without one. */
@Composable
fun AudioTransport(
    playing: Boolean,
    positionMs: Long,
    durationMs: Long,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(SwipeDeleteDimens.SpacingMedium),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(
                R.string.viewer_audio_position_of_duration,
                formatDuration(positionMs),
                formatDuration(durationMs),
            ),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = onToggle,
                modifier = Modifier.defaultMinSize(
                    minWidth = SwipeDeleteDimens.MinTouchTarget,
                    minHeight = SwipeDeleteDimens.MinTouchTarget,
                ),
            ) {
                Text(
                    text = stringResource(
                        if (playing) R.string.viewer_action_pause else R.string.viewer_action_play,
                    ),
                )
            }
            Slider(
                value = if (durationMs > 0) (positionMs.toFloat() / durationMs) else 0f,
                onValueChange = { fraction -> onSeek((fraction * durationMs).toLong()) },
                enabled = durationMs > 0,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** `mm:ss`, or `h:mm:ss` once the item is an hour long. Never negative. */
fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs.coerceAtLeast(0L) / 1000L
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

// core-ktx (String.toUri) is not in the resolved catalog, so Uri.parse stays.
@Suppress("UseKtx")
internal fun mediaUri(item: MediaItem): Uri {
    val value = item.uri
    return if (value.contains("://")) Uri.parse(value) else Uri.fromFile(File(item.path ?: value))
}

internal fun toMedia3Item(item: MediaItem): androidx.media3.common.MediaItem =
    androidx.media3.common.MediaItem.fromUri(mediaUri(item))
