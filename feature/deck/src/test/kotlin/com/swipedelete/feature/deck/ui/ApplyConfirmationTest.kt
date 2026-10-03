package com.swipedelete.feature.deck.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.swipedelete.core.designsystem.SwipeDeleteTheme
import com.swipedelete.feature.deck.R
import com.swipedelete.feature.deck.domain.mediaItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SPEC 6.6: applying staged deletes raises **one** confirmation that lists the
 * count, the bytes, and a preview grid — nothing is destroyed on the swipe itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1000dp-h800dp")
class ApplyConfirmationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val items = listOf(
        mediaItem("i1", "content://tree/src/i1.jpg", sizeBytes = 2L * 1024 * 1024),
        mediaItem("i2", "content://tree/src/i2.jpg", sizeBytes = 3L * 1024 * 1024),
    )

    @Test
    fun `the confirmation names the count and the bytes and previews the staged set`() {
        show()

        composeRule.onNodeWithText("Apply").performClick()

        val message = context.getString(R.string.staging_apply_confirm_message, 2, "5.0 MB")
        composeRule.onNodeWithText(message).assertIsDisplayed()
        assertTrue(message.contains("2"), "the confirmation must name the item count")
        assertTrue(message.contains("5.0 MB"), "the confirmation must name the staged bytes")

        composeRule.onNodeWithTag(StagingPreviewGridTestTag).assertExists()
        assertEquals(
            2,
            composeRule.onAllNodesWithTag(StagingPreviewTileTestTag).fetchSemanticsNodes().size,
            "the preview grid must show one tile per staged item",
        )
    }

    @Test
    fun `dismissing the confirmation applies nothing`() {
        var applied = 0
        show(onApply = { applied++ })

        composeRule.onNodeWithText("Apply").performClick()
        composeRule.onNodeWithText(context.getString(R.string.staging_apply_cancel)).performClick()

        assertEquals(0, applied, "a dismissed confirmation must not commit the batch")
        composeRule.onNodeWithTag(StagingPreviewGridTestTag).assertDoesNotExist()
    }

    @Test
    fun `confirming applies exactly once`() {
        var applied = 0
        show(onApply = { applied++ })

        composeRule.onNodeWithText("Apply").performClick()
        composeRule.onNodeWithText(context.getString(R.string.staging_apply_confirm_action))
            .performClick()

        assertEquals(1, applied, "one confirmation must produce exactly one commit")
    }

    private fun show(onApply: () -> Unit = {}) {
        composeRule.setContent {
            SwipeDeleteTheme {
                StagingStrip(staged = items, onUndo = {}, onApply = onApply)
            }
        }
    }
}
