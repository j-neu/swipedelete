package com.swipedelete.feature.deck.ui

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import com.swipedelete.core.designsystem.SwipeDeleteTheme
import com.swipedelete.feature.deck.domain.Gesture
import com.swipedelete.feature.deck.domain.mediaItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SPEC 5.2 (direction commits on threshold **and** velocity) and SPEC 11.4 /
 * AC-19 (every swipe has a non-gesture equivalent, and the destructive signal
 * is colour + icon + text, never colour alone).
 *
 * The Robolectric Compose rule drives real pointer injection for the
 * threshold/snap-back/fling paths; [SwipeResolver]'s boundary arithmetic is
 * exercised directly so the px thresholds are proven at their exact edges
 * rather than only via a timing-sensitive injected gesture.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1000dp-h800dp-mdpi")
class SwipeGestureTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val item = mediaItem("i1", "content://tree/src/i1.jpg")

    // mdpi: 1 dp == 1 px, matching the card's own token-derived thresholds.
    private val commitDistancePx = 96f
    private val flingDistancePx = 48f
    private val minFlingVelocityPxPerSec = 800f

    // --- SwipeResolver boundary cases (threshold and velocity) ----------------

    @Test
    fun `a slow drag below the commit distance commits nothing`() {
        assertNull(resolve(Offset(-(commitDistancePx - 1f), 0f), Offset.Zero))
    }

    @Test
    fun `a drag at the commit distance commits the delete direction`() {
        assertEquals(SwipeDirection.LEFT, resolve(Offset(-commitDistancePx, 0f), Offset.Zero))
    }

    @Test
    fun `a drag at the commit distance to the right commits keep`() {
        assertEquals(SwipeDirection.RIGHT, resolve(Offset(commitDistancePx, 0f), Offset.Zero))
    }

    @Test
    fun `a drag at the commit distance upwards commits defer`() {
        assertEquals(SwipeDirection.UP, resolve(Offset(0f, -commitDistancePx), Offset.Zero))
    }

    @Test
    fun `a fling below the commit distance commits at the lower fling distance`() {
        assertEquals(
            SwipeDirection.LEFT,
            resolve(Offset(-flingDistancePx, 0f), Offset(-minFlingVelocityPxPerSec, 0f)),
        )
    }

    @Test
    fun `the same short distance without velocity commits nothing`() {
        assertNull(resolve(Offset(-flingDistancePx, 0f), Offset.Zero))
    }

    @Test
    fun `a fling below the fling distance commits nothing`() {
        assertNull(
            resolve(
                Offset(-(flingDistancePx - 1f), 0f),
                Offset(-minFlingVelocityPxPerSec, 0f),
            ),
        )
    }

    private fun resolve(drag: Offset, velocity: Offset): SwipeDirection? = SwipeResolver.resolve(
        drag = drag,
        velocity = velocity,
        commitDistancePx = commitDistancePx,
        flingDistancePx = flingDistancePx,
        minFlingVelocityPxPerSec = minFlingVelocityPxPerSec,
    )

    // --- Pointer-gesture integration -----------------------------------------

    @Test
    fun `swiping left past the commit distance commits delete`() {
        var decided: Gesture? = null
        show { decided = it }

        composeRule.onNodeWithTag(SwipeCardTestTag).performTouchInput { swipeLeft() }

        assertEquals(Gesture.DELETE, decided)
    }

    @Test
    fun `a slow short drag snaps back and commits nothing`() {
        var decided: Gesture? = null
        show { decided = it }

        composeRule.onNodeWithTag(SwipeCardTestTag).performTouchInput {
            down(center)
            var x = center.x
            repeat(4) {
                x -= 5f
                moveTo(Offset(x, center.y), delayMillis = 200)
            }
            up()
        }

        assertNull(decided, "a slow drag under threshold must snap back without a decision")
    }

    @Test
    fun `a fling commits at a lower distance`() {
        var decided: Gesture? = null
        show { decided = it }

        composeRule.onNodeWithTag(SwipeCardTestTag).performTouchInput {
            down(center)
            var x = center.x
            repeat(5) {
                x -= 16f
                moveTo(Offset(x, center.y), delayMillis = 5)
            }
            up()
        }

        assertEquals(Gesture.DELETE, decided, "a fast short drag must commit via velocity")
    }

    // --- Non-gesture equivalents (SPEC 11.4 / AC-19) --------------------------

    @Test
    fun `every direction exposes a custom accessibility action`() {
        var decided: Gesture? = null
        show { decided = it }

        invokeCustomAction("Delete")
        assertEquals(Gesture.DELETE, decided)

        invokeCustomAction("Keep")
        assertEquals(Gesture.KEEP, decided)

        invokeCustomAction("Defer")
        assertEquals(Gesture.DEFER, decided)
    }

    @Test
    fun `the destructive direction shows its label and icon while dragging`() {
        show {}

        composeRule.onNodeWithTag(SwipeCardTestTag).performTouchInput {
            down(center)
            moveTo(Offset(center.x - commitDistancePx * 1.5f, center.y))
        }

        composeRule.onNodeWithText("Delete").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Delete").assertIsDisplayed()
    }

    private fun invokeCustomAction(label: String) {
        val node = composeRule.onNodeWithTag(SwipeCardTestTag).fetchSemanticsNode()
        val action = node.config[SemanticsActions.CustomActions].first { it.label == label }
        composeRule.runOnIdle { action.action() }
    }

    private fun show(onDecision: (Gesture) -> Unit) {
        composeRule.setContent {
            SwipeDeleteTheme {
                SwipeCard(
                    item = item,
                    onDecision = onDecision,
                    modifier = Modifier.size(320.dp).testTag(SwipeCardTestTag),
                )
            }
        }
    }
}
