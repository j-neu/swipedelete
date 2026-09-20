package com.swipedelete.core.designsystem

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.swipedelete.core.designsystem.components.DestructiveAction
import com.swipedelete.core.designsystem.components.requireIconContentDescription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SPEC 5.2 / 11.4: a destructive affordance carries a distinct colour *and* an
 * icon *and* text (colour is never the sole signal), its touch target is at
 * least 48 dp, it exposes a click action so the gesture always has a button
 * equivalent, and it survives RTL layout and 200% font scale.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DestructiveActionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `a destructive action always carries an icon and text, never colour alone`() {
        composeRule.setContent {
            SwipeDeleteTheme {
                DestructiveAction(text = "Delete", onClick = {})
            }
        }

        // Text channel.
        composeRule.onNodeWithText("Delete").assertExists()

        // Icon channel: a non-empty content description, resolved from resources.
        val description = context.getString(R.string.cd_destructive)
        assertTrue(
            "the destructive icon must carry a non-empty content description",
            description.isNotBlank(),
        )
        composeRule.onNodeWithContentDescription(description).assertExists()
    }

    @Test
    fun `destructive touch targets are at least 48 dp`() {
        composeRule.setContent {
            SwipeDeleteTheme {
                DestructiveAction(text = "Delete", onClick = {})
            }
        }

        composeRule.onNodeWithText("Delete")
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
    }

    @Test
    fun `a destructive action is reachable and clickable via its semantics`() {
        composeRule.setContent {
            SwipeDeleteTheme {
                DestructiveAction(text = "Delete", onClick = {})
            }
        }

        composeRule.onNodeWithText("Delete").assertHasClickAction()
    }

    @Test
    fun `the destructive action invokes its onClick when clicked`() {
        var clicks = 0
        composeRule.setContent {
            SwipeDeleteTheme {
                DestructiveAction(text = "Delete", onClick = { clicks++ })
            }
        }

        composeRule.onNodeWithText("Delete").performClick()

        assertEquals("the button-equivalent path must actually fire onClick", 1, clicks)
    }

    @Test
    fun `a blank destructive icon content description is rejected`() {
        // A blank description would silently demote the icon to decorative and
        // leave colour as the sole destructive signal (SPEC 11.4).
        val failure = assertThrows(IllegalArgumentException::class.java) {
            requireIconContentDescription("")
        }

        assertTrue(
            "the rejection must explain the icon is required, was: ${failure.message}",
            failure.message.orEmpty().contains("non-empty"),
        )
    }

    @Test
    fun `renders under RTL layout direction`() {
        composeRule.setContent {
            SwipeDeleteTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    DestructiveAction(text = "Delete", onClick = {})
                }
            }
        }

        composeRule.onNodeWithText("Delete").assertExists()
    }

    @Test
    fun `renders at 200 percent font scale`() {
        composeRule.setContent {
            SwipeDeleteTheme {
                CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                    DestructiveAction(text = "Delete", onClick = {})
                }
            }
        }

        composeRule.onNodeWithText("Delete").assertExists()
    }
}
