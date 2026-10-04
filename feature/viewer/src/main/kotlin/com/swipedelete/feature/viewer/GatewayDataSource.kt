package com.swipedelete.feature.viewer

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.swipedelete.core.model.MediaItem
import java.io.InputStream

/**
 * A Media3 [DataSource] that reads through `StorageGateway.openStream` instead
 * of resolving [MediaItem.uri] itself (finding M1-T15-9).
 *
 * Media3's default sources can only open URIs the platform resolver understands;
 * a SAF item's synthetic `content://tree/...` URI is not one of them, so audio
 * and video would fail for exactly the files this app exists to triage. Routing
 * the bytes through the same gateway the rest of the app uses makes SAF items
 * playable and keeps one read seam.
 *
 * Seeking is supported by reopening the gateway stream and skipping to the
 * requested [DataSpec.position], so the transport keeps working.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class GatewayDataSource(
    private val item: MediaItem,
    private val open: (MediaItem) -> InputStream,
) : DataSource {

    private var stream: InputStream? = null

    override fun addTransferListener(transferListener: TransferListener) = Unit

    override fun open(dataSpec: DataSpec): Long {
        val source = open(item)
        stream = source
        var skipped = 0L
        while (skipped < dataSpec.position) {
            val step = source.skip(dataSpec.position - skipped)
            if (step <= 0) break
            skipped += step
        }
        return dataSpec.length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        stream?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

    override fun getUri(): Uri? = item.uri.takeIf { it.isNotBlank() }?.let(Uri::parse)

    override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()

    override fun close() {
        stream?.close()
        stream = null
    }
}

/** Creates one [GatewayDataSource] per Media3 read session, bound to one item. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class GatewayDataSourceFactory(
    private val item: MediaItem,
    private val open: (MediaItem) -> InputStream,
) : DataSource.Factory {

    override fun createDataSource(): DataSource = GatewayDataSource(item, open)
}
