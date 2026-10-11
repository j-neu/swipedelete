package com.swipedelete.feature.deck.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import com.swipedelete.core.designsystem.SwipeDeleteTheme
import com.swipedelete.core.model.ItemId
import com.swipedelete.core.model.SlotId
import com.swipedelete.feature.deck.domain.DeckAction
import com.swipedelete.feature.deck.domain.DeckState
import com.swipedelete.feature.deck.domain.mediaItem
import com.swipedelete.feature.deck.session.ApplyFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CRITICAL 1: the deck route composes the real M1 components — the swipe card,
 * the accessible action bar, the staging strip, the history strip and the slot
 * dock — not the placeholder lambdas the review found. Tapping the card routes
 * the current item to the viewer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1000dp-h800dp-mdpi")
class DeckRouteCompositionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val current = mediaItem("i1", "content://tree/src/i1.jpg")
    private val stagedItem = mediaItem("i2", "content://tree/src/i2.jpg")
    private var opened: ItemId? = null
    private var applied = false

    private fun show(partial: Boolean = false, failed: List<ApplyFailure> = emptyList()) {
        composeTestRule.setContent {
            SwipeDeleteTheme {
                DeckRouteContent(
                    state = DeckUiState(
                        loading = false,
                        hasSource = true,
                        items = listOf(current, stagedItem),
                        current = current,
                        deck = DeckState(history = listOf(DeckAction.Delete(ItemId("i2"), at = 0))),
                        staged = listOf(stagedItem),
                        slots = listOf(DockSlot(id = SlotId(0), name = "Camera", destinationUri = null)),
                        partialDeck = partial,
                        scanned = 42,
                        failed = failed,
                    ),
                    onDecision = {},
                    onOpen = { opened = it },
                    onSlotSelected = {},
                    onApply = { applied = true },
                    onDiscard = {},
                    onKeep = {},
                    onUndoLast = {},
                    onRevert = {},
                    onFinish = {},
                    onRestore = {},
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `the deck route composes the real card, action bar and slot dock`() {
        show()

        composeTestRule.onNodeWithTag(SwipeCardTestTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(DeckActionBarTestTag).assertIsDisplayed()

        // SPEC 5.2: Move (or a drag-down) reveals the dock, which is otherwise hidden.
        composeTestRule.onNodeWithText("Move").performClick()
        composeTestRule.onNodeWithTag(DeckSlotDockTestTag).assertExists()
    }

    @Test
    fun `a drag down on the card reveals the dock`() {
        show()

        composeTestRule.onNodeWithTag(SwipeCardTestTag).performTouchInput { swipeDown() }
        composeTestRule.onNodeWithTag(DeckSlotDockTestTag).assertExists()
    }

    @Test
    fun `tapping the card opens the current item in the viewer route`() {
        show()

        composeTestRule.onNodeWithTag(SwipeCardTestTag).performClick()

        assertEquals(ItemId("i1"), opened, "the deck route must open the item it is showing")
    }

    @Test
    fun `the staging strip composes and opens the single apply confirmation`() {
        show()

        composeTestRule.onNodeWithText("Apply").performClick()

        composeTestRule.onNodeWithTag(StagingApplyConfirmTestTag).assertExists()
    }

    @Test
    fun `a partial deck announces itself`() {
        show(partial = true)

        composeTestRule.onNodeWithTag(DeckPartialBannerTestTag).assertExists()
    }

    @Test
    fun `items an Apply could not move are named with a plain reason`() {
        show(failed = listOf(ApplyFailure(stagedItem, "protected_root")))

        composeTestRule.onNodeWithTag(DeckApplyFailedBannerTestTag).assertExists()
        composeTestRule.onNodeWithText("protected folder", substring = true).assertExists()
    }
}
