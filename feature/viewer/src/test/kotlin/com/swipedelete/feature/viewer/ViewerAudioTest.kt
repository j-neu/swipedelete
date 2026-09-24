package com.swipedelete.feature.viewer

import android.content.Context
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.swipedelete.core.designsystem.SwipeDeleteTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SPEC 5.4 (audio is in scope, decision 9): audio has transport controls and a
 * duration. The player wiring is device-only; what is pinned here is the
 * transport surface the player is wired to — the position/duration readout and
 * the play/pause toggle.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewerAudioTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `durations are rendered as minutes and seconds`() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("0:07", formatDuration(7_000))
        assertEquals("1:15", formatDuration(75_000))
        assertEquals("1:01:01", formatDuration(3_661_000))
    }

    @Test
    fun `the transport shows the position and the duration`() {
        composeRule.setContent {
            SwipeDeleteTheme {
                AudioTransport(
                    playing = false,
                    positionMs = 0,
                    durationMs = 75_000,
                    onToggle = {},
                    onSeek = {},
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.viewer_audio_position_of_duration, "0:00", "1:15"))
            .assertExists()
    }

    @Test
    fun `the transport toggles playback`() {
        var toggles = 0
        composeRule.setContent {
            SwipeDeleteTheme {
                AudioTransport(
                    playing = false,
                    positionMs = 0,
                    durationMs = 75_000,
                    onToggle = { toggles++ },
                    onSeek = {},
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.viewer_action_play))
            .assertHasClickAction()
            .performClick()

        assertEquals(1, toggles, "the transport's play control must drive the player")
    }

    @Test
    fun `a playing transport offers Pause`() {
        composeRule.setContent {
            SwipeDeleteTheme {
                AudioTransport(
                    playing = true,
                    positionMs = 0,
                    durationMs = 75_000,
                    onToggle = {},
                    onSeek = {},
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.viewer_action_pause)).assertExists()
    }
}
