package com.swipedelete.feature.deck.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.swipedelete.core.common.formatReclaimable
import com.swipedelete.core.designsystem.SwipeDeleteTheme
import com.swipedelete.core.model.DeckStatus
import com.swipedelete.core.model.ItemId
import com.swipedelete.core.model.SessionId
import com.swipedelete.core.model.SlotId
import com.swipedelete.core.storage.fake.FakeStorageGateway
import com.swipedelete.feature.deck.domain.DeckBuilder
import com.swipedelete.feature.deck.domain.DeckQuery
import com.swipedelete.feature.deck.domain.DeckReducer
import com.swipedelete.feature.deck.domain.DeckState
import com.swipedelete.feature.deck.domain.FakeScanPort
import com.swipedelete.feature.deck.domain.FixedClock
import com.swipedelete.feature.deck.domain.Gesture
import com.swipedelete.feature.deck.domain.mediaItem
import com.swipedelete.feature.deck.domain.page
import com.swipedelete.feature.deck.domain.sourceRef
import com.swipedelete.feature.deck.session.DeckStateCodec
import com.swipedelete.feature.deck.session.ExitPrompt
import com.swipedelete.feature.deck.session.FakeDeckSessionDao
import com.swipedelete.feature.deck.session.MutationGate
import com.swipedelete.feature.deck.session.RecordingMutations
import com.swipedelete.feature.deck.session.SessionController
import com.swipedelete.feature.deck.session.SessionSummary
import com.swipedelete.feature.deck.session.sessionSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SPEC 5.5 / 6.5 / AC-9 (M1-T13). The session summary reports the session's
 * counts, plus **measured** bytes (never a sum of file sizes) and quarantine
 * bytes as **reclaimable** — the wording is produced only by
 * [formatReclaimable]/[formatFreed], and a quarantine figure is never rendered
 * as "freed".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1000dp-h800dp")
class SessionSummaryTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val MB = 1024L * 1024L

    // --- Pure summary construction -------------------------------------------

    private fun decidedSession(): DeckState {
        var state = DeckState(items = (1..5).map { ItemId("i$it") })
        state = DeckReducer.reduce(state, Gesture.DELETE)
        state = DeckReducer.reduce(state, Gesture.KEEP)
        state = DeckReducer.reduce(state, Gesture.MOVE, SlotId(2))
        state = DeckReducer.reduce(state, Gesture.MOVE, SlotId(2))
        state = DeckReducer.reduce(state, Gesture.DEFER)
        return state
    }

    @Test
    fun `the summary counts every session decision`() {
        val summary = sessionSummary(
            sessionId = SessionId("s1"),
            state = decidedSession(),
            measuredFreedBytes = 0L,
            reclaimableBytes = 4L * MB,
            status = DeckStatus.ACTIVE,
        )

        assertEquals(5, summary.reviewed)
        assertEquals(1, summary.kept)
        assertEquals(mapOf(SlotId(2) to 2), summary.movedBySlot)
        assertEquals(1, summary.staged)
        assertEquals(1, summary.deferred)
    }

    @Test
    fun `quarantine bytes render reclaimable and never freed`() {
        val summary = sessionSummary(
            sessionId = SessionId("s1"),
            state = decidedSession(),
            measuredFreedBytes = 0L,
            reclaimableBytes = 4L * MB,
            status = DeckStatus.ACTIVE,
        )

        assertEquals(formatReclaimable(4L * MB), summary.reclaimableText)
        assertEquals("4.0 MB reclaimable", summary.reclaimableText)
        assertTrue(summary.reclaimableText.contains("reclaimable"))
        assertTrue(!summary.reclaimableText.contains("freed"))
    }

    @Test
    fun `measured bytes come from the measurement port, never from file sizes`() = runTest {
        val gateway = FakeStorageGateway()
        val source = sourceRef("src")
        gateway.addSource(source)
        val big = listOf(
            mediaItem("i1", "content://tree/src/i1.jpg", sizeBytes = 8L * MB, sourceId = source.id),
            mediaItem("i2", "content://tree/src/i2.jpg", sizeBytes = 8L * MB, sourceId = source.id),
        )
        val controller = SessionController(
            sessions = FakeDeckSessionDao(),
            builder = DeckBuilder({ FakeScanPort(page(big)) }, gateway, FixedClock(0L)),
            clock = FixedClock(0L),
            gate = MutationGate { },
            mutations = RecordingMutations(),
            measuredFreed = { 7L * MB },
            reclaimable = { 0L },
            sessionIds = { SessionId("s1") },
        )
        controller.start(DeckQuery.Folder(source.id))
        controller.decide(Gesture.DELETE)
        controller.decide(Gesture.DELETE)

        val summary = controller.summary()

        assertEquals(
            7L * MB,
            summary.measuredFreedBytes,
            "the measured delta (7 MB) must be reported, not the 16 MB sum of the two staged files",
        )
    }

    @Test
    fun `the session state round-trips through the durable codec`() {
        val state = decidedSession()

        assertEquals(state, DeckStateCodec.decode(DeckStateCodec.encode(state)))
    }

    // --- Rendering ------------------------------------------------------------

    private fun summary(reclaimableBytes: Long = 4L * MB) = SessionSummary(
        sessionId = SessionId("s1"),
        reviewed = 12,
        kept = 3,
        movedBySlot = mapOf(SlotId(2) to 4),
        staged = 5,
        deferred = 1,
        measuredFreedBytes = 0L,
        reclaimableBytes = reclaimableBytes,
        status = DeckStatus.ACTIVE,
    )

    @Test
    fun `the summary shows the reclaimable figure and never the word freed`() {
        show(summary())

        composeRule.onNodeWithText("4.0 MB reclaimable").assertIsDisplayed()
        composeRule.onNodeWithText("freed", substring = true).assertDoesNotExist()
    }

    @Test
    fun `the panic button is prominent and restores everything from the session`() {
        var restored = false
        show(summary(), onRestoreEverything = { restored = true })

        composeRule.onNodeWithText("Restore everything from this session")
            .assertIsDisplayed()
            .performClick()

        assertTrue(restored, "the panic button must call the session-wide restore")
    }

    @Test
    fun `exiting with non-empty staging offers Apply, Keep staged and Discard`() {
        show(summary(), exitPrompt = ExitPrompt(staged = 5))

        composeRule.onNodeWithText("Apply").assertIsDisplayed()
        composeRule.onNodeWithText("Keep staged").assertIsDisplayed()
        composeRule.onNodeWithText("Discard").assertIsDisplayed()
    }

    @Test
    fun `a settled session shows no exit prompt`() {
        show(summary(), exitPrompt = null)

        composeRule.onNodeWithText("Apply").assertDoesNotExist()
    }

    private fun show(
        summary: SessionSummary,
        exitPrompt: ExitPrompt? = null,
        onRestoreEverything: () -> Unit = {},
    ) {
        composeRule.setContent {
            SwipeDeleteTheme {
                SessionSummaryScreen(
                    summary = summary,
                    onRestoreEverything = onRestoreEverything,
                    exitPrompt = exitPrompt,
                )
            }
        }
    }
}
