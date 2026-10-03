package com.swipedelete.feature.deck.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.swipedelete.core.designsystem.SwipeDeleteTheme
import com.swipedelete.core.model.SlotId
import com.swipedelete.core.model.SlotMode
import com.swipedelete.feature.deck.domain.effectiveSlotMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SPEC 5.2 / 5.3 (decision 4) and AC-15, M1-T06.
 *
 * The drag arithmetic is a pure object so the exact 12 dp / 48 dp boundaries are
 * proven directly rather than only through timing-sensitive injected gestures;
 * the Compose rule then proves the deck wiring that consumes the shared
 * designsystem [com.swipedelete.core.designsystem.components.SlotDock].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1000dp-h800dp-mdpi")
class SlotDockTest {

    @get:Rule
    val composeRule = createComposeRule()

    // --- Drag/dock geometry (SPEC 5.2) ---------------------------------------

    @Test
    fun `the drag begins only once the pointer passes 12 dp`() {
        assertFalse(DockGeometry.dragBegan(11f, beginThresholdPx = 12f))
        assertTrue(DockGeometry.dragBegan(12f, beginThresholdPx = 12f))
    }

    @Test
    fun `the nearest slot snaps within 48 dp and releases beyond it`() {
        val centres = listOf(0f, 100f, 200f)
        assertEquals(1, DockGeometry.snapIndex(100f, centres, snapDistancePx = 48f))
        assertEquals(1, DockGeometry.snapIndex(148f, centres, snapDistancePx = 48f))
        assertNull(DockGeometry.snapIndex(149f, centres, snapDistancePx = 48f))
    }

    @Test
    fun `a drop outside the dock cancels and returns the card`() {
        assertNull(DockGeometry.resolveDrop(snapIndex = 2, droppedInsideDock = false))
        assertNull(DockGeometry.resolveDrop(snapIndex = null, droppedInsideDock = true))
        assertEquals(2, DockGeometry.resolveDrop(snapIndex = 2, droppedInsideDock = true))
    }

    @Test
    fun `the dock rises only after the drag crosses the begin threshold`() {
        val state = DockDragState()
        state.onDrag(
            travelledY = 11f,
            pointerX = 0f,
            beginThresholdPx = 12f,
            slotCentresX = listOf(0f),
            snapDistancePx = 48f,
        )
        assertFalse(state.revealed, "a sub-threshold drag must not reveal the dock")

        state.onDrag(
            travelledY = 5f,
            pointerX = 0f,
            beginThresholdPx = 12f,
            slotCentresX = listOf(0f),
            snapDistancePx = 48f,
        )
        assertTrue(state.revealed, "crossing 12 dp must reveal the dock")
    }

    // --- Decision 4: MOVE default, TAG offered -------------------------------

    @Test
    fun `move is the default when a destination is set and tag is used when it is not`() {
        assertEquals(SlotMode.MOVE, effectiveSlotMode("content://tree/export"))
        assertEquals(SlotMode.TAG, effectiveSlotMode(null))
    }

    // --- Deck wiring through the shared designsystem dock --------------------

    private val names = listOf("Screenshots", "Receipts", "Downloads", "Camera", "WhatsApp")

    private fun slots(): List<DockSlot> = names.mapIndexed { index, name ->
        DockSlot(id = SlotId(index), name = name, destinationUri = "content://tree/slot$index")
    }

    @Test
    fun `the deck dock renders every slot through the shared dock`() {
        show(slots(), onSlotSelected = {})

        names.forEach { name -> composeRule.onNodeWithText(name).assertExists() }
    }

    @Test
    fun `selecting an available slot reports it for a move`() {
        var selected: DockSlot? = null
        show(slots(), onSlotSelected = { selected = it })

        composeRule.onNodeWithContentDescription("Move to Downloads").performClick()

        assertEquals("Downloads", selected?.name)
    }

    @Test
    fun `a slot whose destination grant was revoked greys out and reveals its reason on tap`() {
        val reason = "Grant revoked - tap to review access"
        val revoked = slots().toMutableList().also {
            it[2] = it[2].copy(granted = false, unavailableReason = reason)
        }
        var reasonShown: DockSlot? = null
        show(revoked, onSlotSelected = {}, onUnavailable = { reasonShown = it })

        composeRule.onNodeWithContentDescription("Move to Downloads").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(reason).performClick()

        assertEquals("Downloads", reasonShown?.name)
    }

    @Test
    fun `dragging a card onto the nearest slot selects it`() {
        var selected: DockSlot? = null
        show(slots(), onSlotSelected = { selected = it })

        composeRule.onNodeWithTag(DeckSlotDockTestTag).performTouchInput {
            down(center)
            moveTo(Offset(100f, center.y + 60f))
            up()
        }

        assertEquals("Screenshots", selected?.name)
    }

    @Test
    fun `dropping outside the dock cancels the move`() {
        var selected: DockSlot? = null
        show(slots(), onSlotSelected = { selected = it })

        composeRule.onNodeWithTag(DeckSlotDockTestTag).performTouchInput {
            down(center)
            moveTo(Offset(center.x, center.y - 60f))
            up()
        }

        assertNull(selected, "a drop that never revealed the dock must cancel and return the card")
    }

    private fun show(
        slots: List<DockSlot>,
        onSlotSelected: (DockSlot) -> Unit,
        onUnavailable: (DockSlot) -> Unit = {},
    ) {
        composeRule.setContent {
            SwipeDeleteTheme {
                DeckSlotDock(
                    slots = slots,
                    onSlotSelected = onSlotSelected,
                    onUnavailable = onUnavailable,
                )
            }
        }
    }
}
