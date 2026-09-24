package com.swipedelete.feature.viewer

import android.content.Context
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
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
 * SPEC 5.4 / decision 5: inside the viewer, swipe-down **dismisses** and the
 * slot dock is reached through a **button**, never by dragging down. These
 * tests pin both halves: the drag threshold arithmetic, the dock button, and
 * that a downwards drag calls dismiss rather than opening the dock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewerScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `a drag past a quarter of the viewer height dismisses`() {
        assertEquals(true, ViewerDismiss.shouldDismiss(300f, 0f, 1000f))
    }

    @Test
    fun `a short slow drag does not dismiss`() {
        assertEquals(false, ViewerDismiss.shouldDismiss(100f, 10f, 1000f))
    }

    @Test
    fun `a fast downward flick dismisses even when it is short`() {
        assertEquals(true, ViewerDismiss.shouldDismiss(40f, 2000f, 1000f))
    }

    @Test
    fun `an upward drag never dismisses`() {
        assertEquals(false, ViewerDismiss.shouldDismiss(-800f, -3000f, 1000f))
    }

    @Test
    fun `the pager starts on the requested item and falls back to its start index`() {
        val pager = ViewerPager(items = listOf(item(1), item(2), item(3)), startIndex = 0)

        assertEquals(1, pager.initialIndex(item(2).id))
        assertEquals(0, pager.initialIndex(ItemId("missing")))
        assertEquals(2, pager.copy(startIndex = 9).initialIndex(ItemId("missing")))
    }

    @Test
    fun `the dock is reached through a button and never by dragging down`() {
        var dockOpens = 0
        var dismissals = 0
        showViewer(onDismiss = { dismissals++ }, onOpenDock = { dockOpens++ })

        val dockLabel = context.getString(R.string.viewer_action_open_dock)
        composeRule.onNodeWithText(dockLabel).assertHasClickAction().performClick()

        assertEquals(1, dockOpens, "the dock button must open the dock")
        assertEquals(0, dismissals, "tapping the dock button is not a dismissal")
    }

    @Test
    fun `swiping down dismisses the viewer`() {
        var dismissals = 0
        showViewer(onDismiss = { dismissals++ }, onOpenDock = {})

        composeRule.onRoot().performTouchInput { swipeDown() }

        assertEquals(1, dismissals, "decision 5: swipe-down dismisses the viewer")
    }

    private fun showViewer(onDismiss: () -> Unit, onOpenDock: () -> Unit) {
        val pager = ViewerPager(items = listOf(item(1)), startIndex = 0)
        composeRule.setContent {
            SwipeDeleteTheme {
                ViewerScreen(
                    itemId = item(1).id,
                    pager = pager,
                    openStream = { ByteArrayInputStream(JpegFixtures.jpeg()) },
                    onDismiss = onDismiss,
                    onOpenDock = onOpenDock,
                    ioDispatcher = Dispatchers.Unconfined,
                )
            }
        }
    }

    private fun item(n: Int): MediaItem = MediaItem(
        id = ItemId("file:/Pictures/$n.jpg"),
        uri = "content://media/external/images/media/$n",
        path = "/Pictures/$n.jpg",
        relativePath = "Pictures",
        mediaStoreId = n.toLong(),
        mime = "image/jpeg",
        sizeBytes = 1024L,
        width = 4,
        height = 2,
        durationMs = null,
        dateAdded = 0L,
        dateModified = 0L,
        bucket = "Pictures",
        sourceId = SourceId("tree:/Pictures"),
    )
}
