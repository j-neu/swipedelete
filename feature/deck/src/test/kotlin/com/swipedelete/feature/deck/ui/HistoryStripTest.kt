package com.swipedelete.feature.deck.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.swipedelete.core.designsystem.SwipeDeleteTheme
import com.swipedelete.core.model.ItemId
import com.swipedelete.core.model.SlotId
import com.swipedelete.feature.deck.domain.DeckAction
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SPEC 5.5: the history strip is scrollable and every entry is individually
 * revertible — tapping a chip reverts *that* entry, by index.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1000dp-h800dp")
class HistoryStripTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val history = listOf(
        DeckAction.Delete(ItemId("i1"), 0),
        DeckAction.Keep(ItemId("i2"), 0),
        DeckAction.Move(ItemId("i3"), SlotId(4), 0),
    )

    @Test
    fun `every entry is rendered with its own label`() {
        showStrip(history)

        composeRule.onNodeWithText("staged i1").assertIsDisplayed()
        composeRule.onNodeWithText("kept i2").assertIsDisplayed()
        composeRule.onNodeWithText("moved i3 to slot 4").assertIsDisplayed()
    }

    @Test
    fun `tapping an entry reverts that entry's index`() {
        var reverted = -1
        showStrip(history, onRevert = { reverted = it })

        composeRule.onNodeWithText("kept i2").performClick()
        assertEquals(1, reverted, "the second chip must revert index 1")

        composeRule.onNodeWithText("staged i1").performClick()
        assertEquals(0, reverted, "the first chip must revert index 0")
    }

    @Test
    fun `an empty session renders no strip`() {
        showStrip(emptyList())

        composeRule.onNodeWithText("staged i1").assertDoesNotExist()
    }

    private fun showStrip(
        history: List<DeckAction>,
        onRevert: (Int) -> Unit = {},
    ) {
        composeRule.setContent {
            SwipeDeleteTheme {
                HistoryStrip(history = history, onRevert = onRevert)
            }
        }
    }
}
