package com.swipedelete.feature.viewer

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.IntSize
import androidx.test.core.app.ApplicationProvider
import com.swipedelete.core.designsystem.SwipeDeleteTheme
import com.swipedelete.core.model.ItemId
import com.swipedelete.core.model.MediaItem
import com.swipedelete.core.model.SourceId
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SPEC 5.4 / 9.5: the image viewer decodes at full resolution but **never holds
 * a full-resolution bitmap in the pager** — the decode is bounded by the
 * container's target size — and EXIF orientation is applied to the decoded
 * image. Robolectric stubs the final pixel decode, so the assertions are on the
 * production loader's decisions (orientation read from real JPEG bytes, sample
 * size) and on the zoom state the viewer drives.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewerImageTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `an EXIF-rotated JPEG is decoded with its orientation applied`() {
        val loaded = AndroidImageLoader.load(
            open = { ByteArrayInputStream(JpegFixtures.jpeg(orientation = 6)) },
            target = IntSize(200, 200),
        )

        assertNotNull(loaded, "the loader must return an image for a decodable JPEG")
        assertEquals(
            ImageOrientation.ROTATE_90,
            loaded.orientation,
            "orientation 6 must be carried out of the decode so the bitmap can be presented rotated",
        )
    }

    @Test
    fun `a JPEG without EXIF is decoded without rotation`() {
        val loaded = AndroidImageLoader.load(
            open = { ByteArrayInputStream(JpegFixtures.jpeg(orientation = JpegFixtures.NO_EXIF)) },
            target = IntSize(200, 200),
        )

        assertNotNull(loaded)
        assertEquals(ImageOrientation.NORMAL, loaded.orientation)
    }

    @Test
    fun `the decode is bounded by the target size so the pager never holds a full-resolution bitmap`() {
        assertEquals(
            4,
            ImageSizing.sampleSize(4000, 3000, IntSize(1000, 750)),
            "a 4000x3000 image shown at 1000x750 must be downsampled by 4x",
        )
        assertEquals(
            1,
            ImageSizing.sampleSize(1000, 750, IntSize(1000, 750)),
            "an image no larger than the target is decoded at its own resolution",
        )
        assertEquals(
            1,
            ImageSizing.sampleSize(0, 0, IntSize(1000, 750)),
            "unknown source dimensions must not produce a zero or negative sample size",
        )
    }

    @Test
    fun `a double tap toggles between fit and the zoomed scale`() {
        val zoom = ZoomState()

        zoom.doubleTap()
        assertEquals(2f, zoom.scale, "the first double tap zooms in")

        zoom.doubleTap()
        assertEquals(1f, zoom.scale, "the second double tap returns to fit")
        assertEquals(Offset.Zero, zoom.offset)
    }

    @Test
    fun `pinch zoom is clamped to the supported range`() {
        val zoom = ZoomState(minScale = 1f, maxScale = 4f)

        zoom.zoomBy(100f)
        assertEquals(4f, zoom.scale, "zoom must never exceed maxScale")

        zoom.zoomBy(0.001f)
        assertEquals(1f, zoom.scale, "zoom must never fall below minScale")
    }

    @Test
    fun `panning only moves the image while it is zoomed`() {
        val zoom = ZoomState()

        zoom.panBy(Offset(20f, 20f))
        assertEquals(Offset.Zero, zoom.offset, "a fitted image cannot be panned")

        zoom.doubleTap()
        zoom.panBy(Offset(20f, -10f))
        assertEquals(Offset(20f, -10f), zoom.offset)
    }

    @Test
    fun `double tapping the viewer zooms it in`() {
        val zoom = ZoomState()
        composeRule.setContent {
            SwipeDeleteTheme {
                ImageViewer(
                    item = jpegItem(),
                    openStream = { ByteArrayInputStream(JpegFixtures.jpeg(orientation = 1)) },
                    zoom = zoom,
                    ioDispatcher = Dispatchers.Unconfined,
                )
            }
        }

        composeRule.onNodeWithContentDescription(context.getString(R.string.viewer_cd_image))
            .performTouchInput { doubleClick() }

        assertEquals(2f, zoom.scale, "a double tap on the image must zoom it in")
    }

    private fun jpegItem(): MediaItem = MediaItem(
        id = ItemId("file:/Pictures/a.jpg"),
        uri = "content://media/external/images/media/1",
        path = "/Pictures/a.jpg",
        relativePath = "Pictures",
        mediaStoreId = 1L,
        mime = "image/jpeg",
        sizeBytes = 1024L,
        width = 4000,
        height = 3000,
        durationMs = null,
        dateAdded = 0L,
        dateModified = 0L,
        bucket = "Pictures",
        sourceId = SourceId("tree:/Pictures"),
    )
}
