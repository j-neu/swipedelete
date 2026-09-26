package com.swipedelete.feature.viewer

import android.content.Context
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SPEC 5.4 (video), device pass: the [ExoVideoPlayerOps]/[VideoControls] seam
 * against a real Media3 `ExoPlayer`. `ViewerVideoControlsTest` pins the control
 * arithmetic without a device; this asserts the real player's state actually
 * moves the way those controls ask it to.
 *
 * A two-second H.264 MP4 is encoded on the device with `MediaCodec`: no media
 * fixture is checked in, and the test skips (rather than fails) on a device
 * without an AVC encoder.
 */
@RunWith(AndroidJUnit4::class)
class VideoViewerInstrumentedTest {

    private lateinit var context: Context
    private lateinit var file: File
    private lateinit var player: ExoPlayer
    private lateinit var ops: ExoVideoPlayerOps
    private lateinit var controls: VideoControls

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        assumeTrue("no H.264 encoder on this device", avcEncoderAvailable())

        file = File(context.cacheDir, "viewer-video-test.mp4")
        writeTestVideo(file, seconds = 2, fps = 30)

        player = ExoPlayer.Builder(context).build()
        ops = ExoVideoPlayerOps(player)
        controls = VideoControls(ops)

        player.setMediaItem(Media3Item.fromUri(Uri.fromFile(file)))
        player.prepare()
        awaitPlaybackState(Player.STATE_READY)
    }

    @After
    fun tearDown() {
        if (::player.isInitialized) player.release()
        if (::file.isInitialized) file.delete()
    }

    @Test
    fun a_prepared_video_reports_a_positive_duration() {
        assertTrue("duration was ${player.duration}", player.duration > 0L)
        assertTrue("ops must expose the media duration", ops.durationMs > 0L)
    }

    @Test
    fun play_then_pause_transitions_the_real_player() {
        controls.togglePlay()
        awaitIsPlaying(true)
        assertTrue(player.isPlaying)

        controls.togglePlay()
        awaitIsPlaying(false)
        assertFalse(player.isPlaying)
    }

    @Test
    fun speed_and_loop_reach_the_real_player() {
        controls.setSpeed(2f)
        assertEquals(2f, player.playbackParameters.speed, 0.001f)

        controls.toggleLoop()
        assertEquals(Player.REPEAT_MODE_ONE, player.repeatMode)

        controls.toggleLoop()
        assertEquals(Player.REPEAT_MODE_OFF, player.repeatMode)
    }

    @Test
    fun frame_step_pauses_the_real_player_and_moves_one_frame() {
        controls.togglePlay()
        awaitIsPlaying(true)

        val before = player.currentPosition
        controls.stepFrame(FrameStep.FORWARD)

        assertFalse("a frame step must leave the player paused", player.isPlaying)
        assertTrue(
            "expected to advance from $before, was ${player.currentPosition}",
            player.currentPosition > before,
        )
    }

    @Test
    fun a_seek_is_clamped_to_the_media_duration() {
        controls.seekTo(player.duration + 60_000L)

        assertTrue(
            "a seek past the end must clamp, was ${player.currentPosition}",
            player.currentPosition <= player.duration,
        )
    }

    private fun awaitPlaybackState(expected: Int, timeoutMs: Long = 10_000L) {
        val latch = CountDownLatch(1)
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == expected) latch.countDown()
            }
        }
        player.addListener(listener)
        if (player.playbackState == expected) latch.countDown()
        try {
            assertTrue(
                "player never reached playback state $expected (was ${player.playbackState})",
                latch.await(timeoutMs, TimeUnit.MILLISECONDS),
            )
        } finally {
            player.removeListener(listener)
        }
    }

    private fun awaitIsPlaying(expected: Boolean, timeoutMs: Long = 5_000L) {
        val latch = CountDownLatch(1)
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying == expected) latch.countDown()
            }
        }
        player.addListener(listener)
        if (player.isPlaying == expected) latch.countDown()
        try {
            assertTrue(
                "player.isPlaying never became $expected",
                latch.await(timeoutMs, TimeUnit.MILLISECONDS),
            )
        } finally {
            player.removeListener(listener)
        }
    }

    private companion object {
        const val WIDTH = 128
        const val HEIGHT = 96
        const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        const val MICROS_PER_SECOND = 1_000_000L
        const val CHROMA_NEUTRAL = 128.toByte()

        fun avcEncoderAvailable(): Boolean = try {
            MediaCodec.createEncoderByType(MIME).also { it.release() }
            true
        } catch (_: Exception) {
            false
        }

        /**
         * Encodes a solid, slowly-brightening H.264 clip to [file]. Nothing here
         * is a media fixture; a device encoder makes a real, decodable file.
         */
        fun writeTestVideo(file: File, seconds: Int, fps: Int) {
            val format = MediaFormat.createVideoFormat(MIME, WIDTH, HEIGHT).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
                )
                setInteger(MediaFormat.KEY_BIT_RATE, 500_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            val encoder = MediaCodec.createEncoderByType(MIME)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val bufferInfo = MediaCodec.BufferInfo()
            var trackIndex = -1
            var muxerStarted = false

            fun drain(endOfStream: Boolean) {
                var attempts = 0
                while (true) {
                    val outIndex = encoder.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
                    when {
                        outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (!endOfStream || ++attempts > MAX_DRAIN_ATTEMPTS) return
                        }

                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            trackIndex = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }

                        outIndex >= 0 -> {
                            val buffer = encoder.getOutputBuffer(outIndex)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                bufferInfo.size = 0
                            }
                            if (bufferInfo.size > 0 && muxerStarted && buffer != null) {
                                buffer.position(bufferInfo.offset)
                                buffer.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(trackIndex, buffer, bufferInfo)
                            }
                            encoder.releaseOutputBuffer(outIndex, false)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                        }
                    }
                }
            }

            val frameCount = seconds * fps
            val frameDurationUs = MICROS_PER_SECOND / fps
            for (frame in 0 until frameCount) {
                val inIndex = encoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                if (inIndex >= 0) {
                    val buffer = encoder.getInputBuffer(inIndex)!!
                    buffer.clear()
                    val image = encoder.getInputImage(inIndex)
                    if (image != null) {
                        fillFrame(image, frame)
                    } else {
                        buffer.put(ByteArray(buffer.capacity()))
                    }
                    encoder.queueInputBuffer(inIndex, 0, buffer.capacity(), frame * frameDurationUs, 0)
                }
                drain(endOfStream = false)
            }

            val eosIndex = encoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (eosIndex >= 0) {
                encoder.queueInputBuffer(
                    eosIndex,
                    0,
                    0,
                    frameCount * frameDurationUs,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                )
            }
            drain(endOfStream = true)

            muxer.stop()
            muxer.release()
            encoder.stop()
            encoder.release()
        }

        /** Fills an NV12/I420 input image with a frame-varying luma. */
        fun fillFrame(image: Image, frame: Int) {
            val luma = (16 + (frame * 6) % 200).toByte()
            val yPlane = image.planes[0]
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]

            for (row in 0 until HEIGHT) {
                for (col in 0 until WIDTH) {
                    yPlane.buffer.put(row * yPlane.rowStride + col * yPlane.pixelStride, luma)
                }
            }
            for (row in 0 until HEIGHT / 2) {
                for (col in 0 until WIDTH / 2) {
                    uPlane.buffer.put(row * uPlane.rowStride + col * uPlane.pixelStride, CHROMA_NEUTRAL)
                    vPlane.buffer.put(row * vPlane.rowStride + col * vPlane.pixelStride, CHROMA_NEUTRAL)
                }
            }
        }

        const val DEQUEUE_TIMEOUT_US = 10_000L
        const val MAX_DRAIN_ATTEMPTS = 200
    }
}
