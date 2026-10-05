package com.swipedelete.feature.viewer

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import com.swipedelete.core.model.ItemId
import com.swipedelete.core.model.MediaItem
import com.swipedelete.core.model.SourceId
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Finding M1-T15-9: audio and video must read through the gateway, not resolve
 * `item.uri` directly, or SAF (`content://tree/...`) items cannot play.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GatewayDataSourceTest {

    @Test
    fun `reads come from the gateway stream, not the item uri`() {
        val payload = "audio-bytes".toByteArray()
        var opens = 0
        val source = GatewayDataSource(item()) {
            opens++
            ByteArrayInputStream(payload)
        }

        source.open(DataSpec(Uri.parse("content://tree/src/never-resolved.mp3")))

        val buffer = ByteArray(payload.size)
        assertEquals(payload.size, source.read(buffer, 0, buffer.size))
        assertEquals("audio-bytes", String(buffer))
        assertEquals(1, opens, "the bytes must come from the gateway factory")
        source.close()
    }

    @Test
    fun `open seeks to the requested byte position`() {
        val source = GatewayDataSource(item()) { ByteArrayInputStream("0123456789".toByteArray()) }

        source.open(
            DataSpec.Builder()
                .setUri(Uri.parse("content://tree/src/a.mp3"))
                .setPosition(4)
                .build(),
        )

        val buffer = ByteArray(6)
        source.read(buffer, 0, buffer.size)
        assertEquals("456789", String(buffer), "seeking must reopen and skip to the position")
        source.close()
    }

    private fun item(): MediaItem = MediaItem(
        id = ItemId("file:/Music/a.mp3"),
        uri = "content://tree/src/a.mp3",
        path = "/Music/a.mp3",
        relativePath = "Music",
        mediaStoreId = null,
        mime = "audio/mpeg",
        sizeBytes = 1024L,
        width = null,
        height = null,
        durationMs = null,
        dateAdded = 0L,
        dateModified = 0L,
        bucket = "Music",
        sourceId = SourceId("tree:/Music"),
    )
}
