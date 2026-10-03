package com.swipedelete.feature.deck.ui

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.swipedelete.core.designsystem.SwipeDeleteTheme
import com.swipedelete.feature.deck.domain.Gesture
import com.swipedelete.feature.deck.domain.mediaItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SPEC 11.4 / AC-19: no gesture is the only path. Every swipe direction has a
 * real button equivalent in the bottom action bar, the destructive action is a
 * genuine >= 48 dp control, and the bar exposes custom accessibility actions so
 * TalkBack can reach every decision without a gesture.
 *
 * The four swipe directions map onto the bar as: LEFT→Delete, RIGHT→Keep,
 * UP (defer)→Skip, DOWN (reveal dock)→Move.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1000dp-h800dp-mdpi")
class DeckAccessibilityTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `every swipe direction has a button equivalent`() {
        val decided = mutableListOf<Gesture>()
        var moves = 0
        showBar(onDecision = { decided += it }, onMove = { moves++ })

        // LEFT (delete) -> Delete
        composeRule.onNodeWithText("Delete").performClick()
        assertEquals(Gesture.DELETE, decided.last())

        // RIGHT (keep) -> Keep
        composeRule.onNodeWithText("Keep").performClick()
        assertEquals(Gesture.KEEP, decided.last())

        // UP (defer) -> Skip
        composeRule.onNodeWithText("Skip").performClick()
        assertEquals(Gesture.DEFER, decided.last())

        // DOWN (reveal the dock) -> Move, which needs no drag
        composeRule.onNodeWithText("Move").performClick()
        assertEquals(1, moves, "tapping Move must reach the dock without a drag")
        assertEquals(3, decided.size, "Move is a dock affordance, not a deck decision")
    }

    @Test
    fun `the destructive action is a real control at least 48 dp`() {
        showBar()

        composeRule.onNodeWithContentDescription("Delete this item")
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
    }

    @Test
    fun `the bar carries a custom accessibility action for every decision`() {
        val decided = mutableListOf<Gesture>()
        var moves = 0
        showBar(onDecision = { decided += it }, onMove = { moves++ })

        val node = composeRule.onNodeWithTag(DeckActionBarTestTag).fetchSemanticsNode()
        val labels = node.config[SemanticsActions.CustomActions].map { it.label }.toSet()
        assertTrue(
            labels.containsAll(setOf("Delete", "Keep", "Move", "Skip")),
            "the bar must expose Delete/Keep/Move/Skip as custom actions, was $labels",
        )

        val skip = node.config[SemanticsActions.CustomActions].first { it.label == "Skip" }
        composeRule.runOnIdle { skip.action() }
        assertEquals(Gesture.DEFER, decided.single())
    }

    @Test
    fun `pressing a decision button plays the distinct drop haptic`() {
        val haptics = RecordingDeckHaptics()
        showBar(haptics = haptics)

        composeRule.onNodeWithText("Keep").performClick()

        assertEquals(1, haptics.drops, "a committed decision must play the distinct drop pattern")
    }

    @Test
    fun `crossing the swipe threshold ticks and the commit drops`() {
        val haptics = RecordingDeckHaptics()
        var decided: Gesture? = null
        composeRule.setContent {
            SwipeDeleteTheme {
                SwipeCard(
                    item = mediaItem("i1", "content://tree/src/i1.jpg"),
                    onDecision = { decided = it },
                    modifier = Modifier.size(320.dp).testTag(SwipeCardTestTag),
                    haptics = haptics,
                )
            }
        }

        composeRule.onNodeWithTag(SwipeCardTestTag).performTouchInput {
            down(center)
            moveTo(Offset(center.x - 50f, center.y))
        }
        composeRule.waitForIdle()
        assertEquals(0, haptics.ticks, "a sub-threshold drag must not tick")

        composeRule.onNodeWithTag(SwipeCardTestTag).performTouchInput {
            moveTo(Offset(center.x - 200f, center.y))
        }
        composeRule.waitForIdle()
        assertEquals(1, haptics.ticks, "crossing the commit threshold must play a light tick")

        composeRule.onNodeWithTag(SwipeCardTestTag).performTouchInput { up() }
        composeRule.waitForIdle()
        assertEquals(1, haptics.drops, "committing the direction must play the distinct drop pattern")
        assertEquals(Gesture.DELETE, decided)
    }

    private fun showBar(
        onDecision: (Gesture) -> Unit = {},
        onMove: () -> Unit = {},
        haptics: DeckHaptics = NoOpDeckHaptics,
    ) {
        composeRule.setContent {
            SwipeDeleteTheme {
                DeckActionBar(
                    onDecision = onDecision,
                    onMove = onMove,
                    haptics = haptics,
                )
            }
        }
    }
}

/** A [DeckHaptics] that counts each distinct pattern so the wiring is observable. */
private class RecordingDeckHaptics : DeckHaptics {
    var ticks: Int = 0
    var drops: Int = 0
    var failures: Int = 0

    override fun threshold() {
        ticks++
    }

    override fun drop() {
        drops++
    }

    override fun failure() {
        failures++
    }
}
