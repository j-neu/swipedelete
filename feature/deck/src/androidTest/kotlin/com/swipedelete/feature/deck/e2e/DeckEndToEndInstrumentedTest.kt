package com.swipedelete.feature.deck.e2e

import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.swipedelete.core.model.JournalState
import com.swipedelete.core.model.OpKind
import com.swipedelete.feature.deck.ui.SwipeCardTestTag
import com.swipedelete.feature.deck.ui.ThumbnailImageTestTag
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M1 acceptance, end to end on a device: the assembled deck, driven by touch,
 * over real image files reached through a real SAF tree, with a real on-disk
 * journal and a real quarantine directory (see [DeckHarness] for what is and is
 * not production wiring).
 *
 * The files live in this test app's private cache directory, so the test never
 * touches the device's shared storage.
 */
@RunWith(AndroidJUnit4::class)
class DeckEndToEndInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    // Built before the rule launches the activity, so the first frame already
    // renders the real deck.
    private val harness = DeckHarness(context, itemCount = 4)

    @get:Rule
    val rule = createAndroidComposeRule<DeckHostActivity>()

    private val state get() = harness.viewModel.state.value

    @After
    fun tearDown() = harness.close()

    @Test
    fun a_swiped_card_is_only_staged_and_Apply_moves_it_to_quarantine_with_a_journal_row() {
        val deleted = harness.files[0]
        val kept = harness.files[1]
        val originalBytes = deleted.readBytes()
        rule.waitUntil(TIMEOUT) { state.current != null }

        rule.onNodeWithTag(SwipeCardTestTag).performTouchInput { swipeLeft() }
        rule.waitUntil(TIMEOUT) { state.staged.size == 1 }
        assertTrue(deleted.exists(), "a swipe only stages; the file must still be in place")

        rule.onNodeWithTag(SwipeCardTestTag).performTouchInput { swipeRight() }
        rule.waitUntil(TIMEOUT) { state.deck.kept.size == 1 }

        rule.onNodeWithText("Apply").performClick()
        rule.onNodeWithText("Move to quarantine").performClick()
        rule.waitUntil(TIMEOUT) { state.staged.isEmpty() }

        assertTrue(state.failed.isEmpty(), "Apply reported failures: ${state.failed}")
        assertFalse(deleted.exists(), "the applied item must have left its folder")
        assertTrue(kept.exists(), "a kept item is never touched")
        val copy = harness.quarantineRoot.walkTopDown().single { it.isFile && it.name == deleted.name }
        assertContentEquals(originalBytes, copy.readBytes(), "the quarantine copy must be byte-identical")

        val rows = runBlocking { harness.db.journalDao().page(limit = 10, offset = 0) }
        assertEquals(listOf(OpKind.QUARANTINE to JournalState.DONE), rows.map { it.opKind to it.state })
    }

    @Test
    fun the_card_shows_the_real_image_of_a_folder_grant_item() {
        rule.waitUntil(TIMEOUT) { state.current != null }

        // The tag is only on a decoded image; the blank fallback surface has none.
        rule.waitUntil(TIMEOUT) {
            rule.onAllNodesWithTag(ThumbnailImageTestTag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** AC-3 through the UI: what the deck quarantined comes back, byte-identical. */
    @Test
    fun restore_everything_from_the_summary_puts_the_file_back_byte_identical() {
        val deleted = harness.files[0]
        val originalBytes = deleted.readBytes()
        rule.waitUntil(TIMEOUT) { state.current != null }
        rule.onNodeWithTag(SwipeCardTestTag).performTouchInput { swipeLeft() }
        rule.waitUntil(TIMEOUT) { state.staged.size == 1 }
        rule.onNodeWithText("Apply").performClick()
        rule.onNodeWithText("Move to quarantine").performClick()
        rule.waitUntil(TIMEOUT) { state.staged.isEmpty() && !deleted.exists() }

        rule.onNodeWithText("Finish session").performClick()
        rule.waitUntil(TIMEOUT) { state.finished }
        rule.onNodeWithText("reclaimable", substring = true).assertExists()
        rule.onNodeWithText("Restore everything from this session").performClick()

        // The restore is finished when the quarantine copy is gone; the file
        // appearing is too early, since it is created empty and then written.
        fun copyGone() = harness.quarantineRoot.walkTopDown().none { it.isFile && it.name == deleted.name }
        rule.waitUntil(TIMEOUT) { copyGone() || state.notRestored > 0 || state.error != null }
        assertTrue(deleted.exists(), "notRestored=${state.notRestored} error=${state.error}")
        assertContentEquals(originalBytes, deleted.readBytes())
        assertEquals(0, state.notRestored)
        assertTrue(copyGone(), "a restored item leaves quarantine")
    }

    private companion object {
        const val TIMEOUT = 15_000L
    }
}
