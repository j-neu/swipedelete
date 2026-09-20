package com.swipedelete.core.designsystem

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.swipedelete.core.designsystem.components.SlotDock
import com.swipedelete.core.designsystem.components.SlotSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SPEC 5.2 / 11.4: the dock is reachable by tapping "Move" without dragging, so
 * :feature:deck already has a non-drag path to every slot. This is a smoke test
 * of the shared public surface M1 consumes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SlotDockTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `slot dock renders every slot name and offers a non-drag move affordance`() {
        val names = listOf("Screenshots", "Receipts", "Downloads", "Camera", "WhatsApp")
        val slots = names.map { SlotSpec(name = it) }

        composeRule.setContent {
            SwipeDeleteTheme {
                SlotDock(slots = slots, onSlotMove = {})
            }
        }

        names.forEach { name ->
            composeRule.onNodeWithText(name).assertExists()
        }

        val moveLabel = context.getString(R.string.action_move)
        val moveNodes = composeRule.onAllNodesWithText(moveLabel).fetchSemanticsNodes()
        assertEquals(
            "every slot must expose a non-drag move affordance",
            names.size,
            moveNodes.size,
        )
        moveNodes.forEach { node ->
            assertTrue(
                "the move affordance must be a click action, not drag-only",
                node.config.contains(SemanticsActions.OnClick),
            )
        }
    }

    @Test
    fun `the move affordance reports the exact slot it was invoked for`() {
        val names = listOf("Screenshots", "Receipts", "Downloads", "Camera", "WhatsApp")
        val slots = names.map { SlotSpec(name = it) }
        var moved: SlotSpec? = null

        composeRule.setContent {
            SwipeDeleteTheme {
                SlotDock(slots = slots, onSlotMove = { moved = it })
            }
        }

        val target = "Downloads"
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.slot_move_to, target))
            .performClick()

        assertEquals(
            "the non-drag affordance must report the slot it was invoked for",
            SlotSpec(name = target),
            moved,
        )
    }
}
