package com.swipedelete.feature.viewer

import android.content.Context
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.swipedelete.core.designsystem.SwipeDeleteTheme
import com.swipedelete.core.model.ItemId
import com.swipedelete.core.model.MediaItem
import com.swipedelete.core.model.SourceId
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SPEC 5.4 (documents/other and unsupported): a document shows a metadata card
 * with an "Open with" hand-off; an undecodable item shows an honest error naming
 * the codec/MIME plus "Open with" — never a blank frame.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewerUnsupportedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `documents and unknown types are routed to the document card`() {
        assertEquals(ViewerContentKind.DOCUMENT, viewerContentKind("application/pdf"))
        assertEquals(ViewerContentKind.DOCUMENT, viewerContentKind("text/plain"))
        assertEquals(ViewerContentKind.DOCUMENT, viewerContentKind(""))
    }

    @Test
    fun `the document card names the mime, shows metadata and hands off with Open with`() {
        var opened: MediaItem? = null
        showCard(documentItem(), UnsupportedReason.DOCUMENT, onOpenWith = { opened = it })

        composeRule.onNodeWithText("application/pdf", substring = true).assertExists()
        composeRule.onNodeWithText("report.pdf", substring = true).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.viewer_action_open_with))
            .assertHasClickAction()
            .performClick()

        assertEquals(documentItem().id, opened?.id)
    }

    @Test
    fun `an undecodable item names its mime and offers Open with rather than a blank frame`() {
        showCard(imageItem(mime = "image/avif"), UnsupportedReason.UNDECODABLE, onOpenWith = {})

        composeRule.onNodeWithText("image/avif", substring = true).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.viewer_action_open_with)).assertExists()
    }

    @Test
    fun `an image the loader cannot decode falls back to the honest card, never a blank frame`() {
        val item = imageItem(mime = "image/jpeg")
        composeRule.setContent {
            SwipeDeleteTheme {
                ViewerScreen(
                    itemId = item.id,
                    pager = ViewerPager(items = listOf(item)),
                    openStream = { ByteArrayInputStream(ByteArray(0)) },
                    onDismiss = {},
                    onOpenDock = {},
                    imageLoader = ImageLoader { _, _ -> null },
                    ioDispatcher = Dispatchers.Unconfined,
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("image/jpeg", substring = true).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.viewer_action_open_with)).assertExists()
    }

    private fun showCard(item: MediaItem, reason: UnsupportedReason, onOpenWith: (MediaItem) -> Unit) {
        composeRule.setContent {
            SwipeDeleteTheme {
                UnsupportedCard(item = item, reason = reason, onOpenWith = onOpenWith)
            }
        }
        composeRule.waitForIdle()
    }

    private fun documentItem(): MediaItem = MediaItem(
        id = ItemId("file:/Download/report.pdf"),
        uri = "content://media/external/file/7",
        path = "/Download/report.pdf",
        relativePath = "Download",
        mediaStoreId = 7L,
        mime = "application/pdf",
        sizeBytes = 40_000L,
        width = null,
        height = null,
        durationMs = null,
        dateAdded = 0L,
        dateModified = 0L,
        bucket = "Download",
        sourceId = SourceId("tree:/Download"),
    )

    private fun imageItem(mime: String): MediaItem = MediaItem(
        id = ItemId("file:/Pictures/odd.bin"),
        uri = "content://media/external/images/media/8",
        path = "/Pictures/odd.bin",
        relativePath = "Pictures",
        mediaStoreId = 8L,
        mime = mime,
        sizeBytes = 2048L,
        width = 100,
        height = 50,
        durationMs = null,
        dateAdded = 0L,
        dateModified = 0L,
        bucket = "Pictures",
        sourceId = SourceId("tree:/Pictures"),
    )
}
