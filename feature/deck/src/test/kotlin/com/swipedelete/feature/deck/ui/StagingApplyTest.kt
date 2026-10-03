package com.swipedelete.feature.deck.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.swipedelete.core.common.formatBytes
import com.swipedelete.core.designsystem.SwipeDeleteTheme
import com.swipedelete.core.model.BatchId
import com.swipedelete.core.model.ItemId
import com.swipedelete.core.model.MediaItem
import com.swipedelete.core.storage.QuarantineResult
import com.swipedelete.core.storage.StorageGateway
import com.swipedelete.core.storage.fake.FakeStorageGateway
import com.swipedelete.feature.deck.R
import com.swipedelete.feature.deck.domain.DeckReducer
import com.swipedelete.feature.deck.domain.DeckState
import com.swipedelete.feature.deck.domain.Gesture
import com.swipedelete.feature.deck.domain.mediaItem
import com.swipedelete.feature.deck.session.MutationGate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SPEC 5.2 / decision 3 (staged-by-default) and SPEC 6.6 / D6 (Apply is one
 * confirmation, gated on recovery readiness, and executes the whole staged set
 * in a single `quarantine` call).
 *
 * The swipe surface emits a [Gesture]; the deck reducer turns `DELETE` into a
 * staged item and *never* touches storage. Only the Apply path — after
 * `RecoveryCoordinator.awaitReady()` via the [MutationGate] seam — calls
 * [StorageGateway.quarantine], exactly once, with every staged item.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1000dp-h800dp")
class StagingApplyTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val items = listOf(
        mediaItem("i1", "content://tree/src/i1.jpg"),
        mediaItem("i2", "content://tree/src/i2.jpg"),
    )

    @Test
    fun `a swipe stages but performs no gateway call, and Apply quarantines the whole set once`() {
        val recording = RecordingGateway(FakeStorageGateway(initialItems = items))
        val applier = StagingApplier(gateway = recording, readiness = MutationGate { })
        showDeck(recording, applier)

        composeRule.onNodeWithTag(SwipeCardTestTag).performTouchInput { swipeLeft() }
        assertEquals(0, recording.quarantineCalls, "a swipe must stage, never touch the gateway")
        composeRule.onNodeWithText("1 to delete").assertExists()

        composeRule.onNodeWithTag(SwipeCardTestTag).performTouchInput { swipeLeft() }
        assertEquals(0, recording.quarantineCalls, "the second swipe must stage too")
        composeRule.onNodeWithText("2 to delete").assertExists()

        composeRule.onNodeWithText("Apply").performClick()

        val message = context.getString(
            R.string.staging_apply_confirm_message,
            2,
            formatBytes(items.sumOf { it.sizeBytes }),
        )
        composeRule.onNodeWithText(message).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.staging_apply_confirm_action))
            .performClick()

        assertEquals(1, recording.quarantineCalls, "Apply must quarantine the whole set exactly once")
        assertEquals(
            setOf(ItemId("i1"), ItemId("i2")),
            recording.lastItems?.map { it.id }?.toSet(),
        )
    }

    @Test
    fun `the apply path awaits recovery readiness before the destructive call`() = runTest {
        val order = mutableListOf<String>()
        val recording = RecordingGateway(
            delegate = FakeStorageGateway(initialItems = items),
            onQuarantine = { order += "quarantine" },
        )
        val applier = StagingApplier(
            gateway = recording,
            readiness = MutationGate { order += "awaitReady" },
        )

        applier.apply(items)

        assertEquals(
            listOf("awaitReady", "quarantine"),
            order,
            "D6: readiness must be awaited before quarantine",
        )
    }

    private fun showDeck(recording: RecordingGateway, applier: StagingApplier) {
        composeRule.setContent {
            SwipeDeleteTheme {
                var deck by remember { mutableStateOf(DeckState(items = items.map { it.id })) }
                Column {
                    val current = deck.items.getOrNull(deck.cursor)
                        ?.let { id -> items.first { it.id == id } }
                    if (current != null) {
                        SwipeCard(
                            item = current,
                            onDecision = { deck = DeckReducer.reduce(deck, it) },
                            modifier = Modifier.size(320.dp).testTag(SwipeCardTestTag),
                        )
                    }
                    val stagedItems = deck.staged.mapNotNull { id -> items.firstOrNull { it.id == id } }
                    StagingStrip(
                        staged = stagedItems,
                        onUndo = { deck = DeckReducer.undo(deck) },
                        onApply = { runBlocking { applier.apply(stagedItems) } },
                    )
                }
            }
        }
    }
}

/**
 * A [StorageGateway] that counts `quarantine` calls and remembers the exact set
 * it was handed, then delegates to the in-memory fake.
 */
private class RecordingGateway(
    private val delegate: StorageGateway,
    private val onQuarantine: (List<MediaItem>) -> Unit = {},
) : StorageGateway by delegate {

    var quarantineCalls: Int = 0
        private set

    var lastItems: List<MediaItem>? = null
        private set

    override suspend fun quarantine(items: List<MediaItem>, batch: BatchId): QuarantineResult {
        quarantineCalls++
        lastItems = items
        onQuarantine(items)
        return delegate.quarantine(items, batch)
    }
}
