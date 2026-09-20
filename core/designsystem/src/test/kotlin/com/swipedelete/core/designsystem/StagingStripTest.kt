package com.swipedelete.core.designsystem

import android.content.Context
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.swipedelete.core.designsystem.components.StagingStrip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SPEC 5.2 / 6.6: a staged delete is never one-frame-commit — it enters a
 * visible staging strip ("3 to delete · Undo"). Undo is recoverable; "Apply"
 * commits the staged deletions, so it is destructive and must render with icon
 * **and** text **and** the destructive colour, which it inherits by going
 * through `DestructiveAction`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StagingStripTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `staging strip shows the item count and an undo affordance with text`() {
        var undoCount = 0
        composeRule.setContent {
            SwipeDeleteTheme {
                StagingStrip(count = 3, onUndo = { undoCount++ })
            }
        }

        val countText = context.getString(R.string.staging_strip_count, 3)
        val undoText = context.getString(R.string.action_undo)

        composeRule.onNodeWithText(countText).assertExists()
        composeRule.onNodeWithText(undoText).assertExists().assertHasClickAction()

        composeRule.onNodeWithText(undoText).performClick()
        assertEquals("the undo affordance must actually fire onUndo", 1, undoCount)
    }

    @Test
    fun `the apply affordance renders the destructive action with an icon and its label`() {
        composeRule.setContent {
            SwipeDeleteTheme {
                StagingStrip(count = 3, onUndo = {}, onApply = {})
            }
        }

        // Text channel: the caller-visible apply label.
        val applyText = context.getString(R.string.action_apply)
        composeRule.onNodeWithText(applyText).assertExists()

        // Icon channel: a non-empty content description, so the commit path is
        // never colour-or-text alone. Colour is guaranteed structurally by
        // rendering through DestructiveAction.
        val description = context.getString(R.string.cd_destructive)
        assertTrue(
            "the apply affordance's icon must carry a non-empty content description",
            description.isNotBlank(),
        )
        composeRule.onNodeWithContentDescription(description).assertExists()
    }

    @Test
    fun `the apply affordance invokes its callback when clicked`() {
        var applyCount = 0
        composeRule.setContent {
            SwipeDeleteTheme {
                StagingStrip(count = 3, onUndo = {}, onApply = { applyCount++ })
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.action_apply)).performClick()

        assertEquals("the commit affordance must actually fire onApply", 1, applyCount)
    }
}
