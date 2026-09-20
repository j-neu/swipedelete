package com.swipedelete.core.designsystem

import android.content.Context
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.swipedelete.core.designsystem.components.StagingStrip
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SPEC 5.2: a staged delete is never one-frame-commit — it enters a visible
 * staging strip ("3 to delete · Undo") with a text undo affordance.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StagingStripTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `staging strip shows the item count and an undo affordance with text`() {
        composeRule.setContent {
            SwipeDeleteTheme {
                StagingStrip(count = 3, onUndo = {})
            }
        }

        val countText = context.getString(R.string.staging_strip_count, 3)
        val undoText = context.getString(R.string.action_undo)

        composeRule.onNodeWithText(countText).assertExists()
        composeRule.onNodeWithText(undoText).assertExists().assertHasClickAction()
    }
}
