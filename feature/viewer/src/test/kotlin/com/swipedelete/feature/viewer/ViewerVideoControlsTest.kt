package com.swipedelete.feature.viewer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SPEC 5.4 (video): play/pause, scrub, ±10 s, speed 0.25×–3×, frame step, loop
 * toggle and audio-track selection, with audio always available.
 *
 * The ExoPlayer wiring is device-only ([VideoViewerInstrumentedTest]); what is
 * pinned here is the control arithmetic: every transport callback reaches the
 * expected player operation, in the expected order, with the documented bounds.
 */
class ViewerVideoControlsTest {

    /** Records each player operation so ordering and values can be asserted. */
    private class RecordingPlayer(
        private var playing: Boolean = false,
        private var positionMs: Long = 0L,
        override var durationMs: Long = 60_000L,
        private var speed: Float = 1f,
        private var looping: Boolean = false,
        override var frameDurationMs: Long = 40L,
        override var audioTrackCount: Int = 2,
    ) : VideoPlayerOps {
        val calls = mutableListOf<String>()

        override val isPlaying: Boolean get() = playing
        override val currentPositionMs: Long get() = positionMs
        override val playbackSpeed: Float get() = speed
        override val isLooping: Boolean get() = looping

        override fun play() {
            calls += "play"
            playing = true
        }

        override fun pause() {
            calls += "pause"
            playing = false
        }

        override fun seekTo(positionMs: Long) {
            calls += "seekTo:$positionMs"
            this.positionMs = positionMs
        }

        override fun setPlaybackSpeed(speed: Float) {
            calls += "speed:$speed"
            this.speed = speed
        }

        override fun setLooping(looping: Boolean) {
            calls += "loop:$looping"
            this.looping = looping
        }

        override fun selectAudioTrack(index: Int) {
            calls += "audio:$index"
        }
    }

    @Test
    fun `play pause toggles the player`() {
        val paused = RecordingPlayer(playing = false)
        VideoControls(paused).togglePlay()
        assertEquals(listOf("play"), paused.calls)

        val playing = RecordingPlayer(playing = true)
        VideoControls(playing).togglePlay()
        assertEquals(listOf("pause"), playing.calls)
    }

    @Test
    fun `scrub seeks to the requested position`() {
        val player = RecordingPlayer(positionMs = 5_000L, durationMs = 60_000L)

        VideoControls(player).seekTo(12_345L)

        assertEquals(listOf("seekTo:12345"), player.calls)
    }

    @Test
    fun `scrub is clamped to the media bounds`() {
        val player = RecordingPlayer(durationMs = 60_000L)
        val controls = VideoControls(player)

        controls.seekTo(-500L)
        controls.seekTo(999_999L)

        assertEquals(listOf("seekTo:0", "seekTo:60000"), player.calls)
    }

    @Test
    fun `skip seeks ten seconds in both directions`() {
        val forward = RecordingPlayer(positionMs = 30_000L, durationMs = 60_000L)
        VideoControls(forward).skipForward()
        assertEquals(listOf("seekTo:40000"), forward.calls)

        val backward = RecordingPlayer(positionMs = 30_000L, durationMs = 60_000L)
        VideoControls(backward).skipBackward()
        assertEquals(listOf("seekTo:20000"), backward.calls)
    }

    @Test
    fun `skip near the start stops at zero rather than seeking negative`() {
        val player = RecordingPlayer(positionMs = 3_000L, durationMs = 60_000L)

        VideoControls(player).skipBackward()

        assertEquals(listOf("seekTo:0"), player.calls)
    }

    @Test
    fun `speed clamps at the ends of the supported range`() {
        val player = RecordingPlayer()
        val controls = VideoControls(player)

        controls.setSpeed(0.1f)
        controls.setSpeed(3.5f)
        controls.setSpeed(1.5f)

        assertEquals(listOf("speed:0.25", "speed:3.0", "speed:1.5"), player.calls)
        assertEquals(0.25f, VideoControls.clampSpeed(0.01f))
        assertEquals(3.0f, VideoControls.clampSpeed(10f))
        assertEquals(1.0f, VideoControls.clampSpeed(1.0f))
    }

    @Test
    fun `frame step pauses first then advances exactly one frame`() {
        val player = RecordingPlayer(
            playing = true,
            positionMs = 40_000L,
            durationMs = 60_000L,
            frameDurationMs = 40L,
        )

        VideoControls(player).stepFrame(FrameStep.FORWARD)

        assertEquals(
            listOf("pause", "seekTo:40040"),
            player.calls,
            "a frame step must pause before it moves, so the step is observable",
        )
        assertFalse(player.isPlaying, "stepping a frame leaves the player paused")
    }

    @Test
    fun `frame step backward never crosses the start`() {
        val player = RecordingPlayer(
            playing = true,
            positionMs = 10L,
            durationMs = 60_000L,
            frameDurationMs = 40L,
        )

        VideoControls(player).stepFrame(FrameStep.BACKWARD)

        assertEquals(listOf("pause", "seekTo:0"), player.calls)
    }

    @Test
    fun `loop toggle flips the repeat state`() {
        val player = RecordingPlayer(looping = false)
        val controls = VideoControls(player)

        controls.toggleLoop()
        controls.toggleLoop()

        assertEquals(listOf("loop:true", "loop:false"), player.calls)
    }

    @Test
    fun `audio track selection reaches the player so audio is never disabled`() {
        val player = RecordingPlayer(audioTrackCount = 2)

        VideoControls(player).selectAudioTrack(1)

        assertEquals(listOf("audio:1"), player.calls)
    }

    @Test
    fun `an out of range audio track is ignored`() {
        val player = RecordingPlayer(audioTrackCount = 1)

        VideoControls(player).selectAudioTrack(3)

        assertEquals(emptyList(), player.calls)
    }

    @Test
    fun `the speed presets stay inside the supported range and are labelled plainly`() {
        assertTrue(
            SPEED_PRESETS.all { it in VideoControls.MIN_SPEED..VideoControls.MAX_SPEED },
            "every preset must be inside the clamp range, otherwise the control lies",
        )
        assertEquals("0.25", formatSpeed(0.25f))
        assertEquals("1", formatSpeed(1f))
        assertEquals("1.5", formatSpeed(1.5f))
        assertEquals("3", formatSpeed(3f))
    }

    @Test
    fun `the viewer routes each mime to its content kind`() {
        assertEquals(ViewerContentKind.VIDEO, viewerContentKind("video/mp4"))
        assertEquals(ViewerContentKind.VIDEO, viewerContentKind("video/quicktime"))
        assertEquals(ViewerContentKind.ANIMATED_IMAGE, viewerContentKind("image/gif"))
        assertEquals(ViewerContentKind.AUDIO, viewerContentKind("audio/mpeg"))
        assertEquals(ViewerContentKind.IMAGE, viewerContentKind("image/jpeg"))
    }
}
