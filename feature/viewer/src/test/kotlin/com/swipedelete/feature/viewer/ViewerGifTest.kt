package com.swipedelete.feature.viewer

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.swipedelete.core.designsystem.SwipeDeleteTheme
import com.swipedelete.core.model.ItemId
import com.swipedelete.core.model.MediaItem
import com.swipedelete.core.model.SourceId
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SPEC 5.4: an animated GIF/WebP plays and loops, with play/pause. The platform
 * animation is wrapped so the transport is testable: the test drives the real
 * [GifViewer] against a recording animation and asserts the play/pause
 * affordance actually starts and stops playback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewerGifTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `an animated image starts playing on open and offers Pause`() {
        val animation = RecordingAnimation()

        showGif(animation)

        assertEquals(
            listOf("start"),
            animation.calls,
            "an animated image must begin playing without the user having to start it",
        )
        assertTrue(animation.isRunning)
        composeRule.onNodeWithText(context.getString(R.string.viewer_action_pause))
            .assertExists()
            .assertHasClickAction()
    }

    @Test
    fun `tapping Pause stops the animation and the affordance becomes Play`() {
        val animation = RecordingAnimation()
        showGif(animation)

        composeRule.onNodeWithText(context.getString(R.string.viewer_action_pause)).performClick()
        composeRule.waitForIdle()

        assertEquals(listOf("start", "stop"), animation.calls, "Pause must stop the platform animation")
        composeRule.onNodeWithText(context.getString(R.string.viewer_action_play)).assertExists()
    }

    @Test
    fun `tapping Play after Pause resumes the animation`() {
        val animation = RecordingAnimation()
        showGif(animation)

        composeRule.onNodeWithText(context.getString(R.string.viewer_action_pause)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(context.getString(R.string.viewer_action_play)).performClick()
        composeRule.waitForIdle()

        assertEquals(
            listOf("start", "stop", "start"),
            animation.calls,
            "the transport must be a true toggle, not a one-way pause",
        )
    }

    private fun showGif(animation: RecordingAnimation) {
        composeRule.setContent {
            SwipeDeleteTheme {
                GifViewer(
                    item = gifItem(),
                    openStream = { ByteArrayInputStream(JpegFixtures.jpeg()) },
                    loader = GifLoader { LoadedAnimation(ColorDrawable(Color.BLACK), animation) },
                    ioDispatcher = Dispatchers.Unconfined,
                )
            }
        }
    }

    private fun gifItem(): MediaItem = MediaItem(
        id = ItemId("file:/Pictures/a.gif"),
        uri = "content://media/external/images/media/2",
        path = "/Pictures/a.gif",
        relativePath = "Pictures",
        mediaStoreId = 2L,
        mime = "image/gif",
        sizeBytes = 2048L,
        width = 320,
        height = 240,
        durationMs = null,
        dateAdded = 0L,
        dateModified = 0L,
        bucket = "Pictures",
        sourceId = SourceId("tree:/Pictures"),
    )

    private class RecordingAnimation : LoopingAnimation {
        val calls = mutableListOf<String>()

        override fun start() {
            calls += "start"
        }

        override fun stop() {
            calls += "stop"
        }

        override val isRunning: Boolean get() = calls.lastOrNull() == "start"
    }
}
