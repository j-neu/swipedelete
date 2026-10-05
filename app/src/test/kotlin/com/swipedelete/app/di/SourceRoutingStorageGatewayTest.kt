package com.swipedelete.app.di

import com.swipedelete.core.model.ItemId
import com.swipedelete.core.model.MediaItem
import com.swipedelete.core.model.SourceId
import com.swipedelete.core.model.SourceKind
import com.swipedelete.core.model.SourceRef
import com.swipedelete.core.storage.Destination
import com.swipedelete.core.storage.fake.FakeStorageGateway
import com.swipedelete.core.storage.routing.SourceRoutingStorageGateway
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CRITICAL 1: one gateway over two backends. A SAF folder must be walked by the
 * SAF gateway and a MediaStore collection by the MediaStore gateway, while item
 * operations follow the item's identity (`mediaStoreId`), not source order.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SourceRoutingStorageGatewayTest {

    @Test
    fun `each source is walked by its own gateway and item ops route by identity`() = runTest {
        val saf = FakeStorageGateway()
        val safSource = source("saf", SourceKind.SAF, "content://tree/saf")
        val safItem = item("saf-1", safSource.id, "content://tree/saf/a.jpg", mediaStoreId = null)
        saf.addSource(safSource)
        saf.register(safItem)

        val media = FakeStorageGateway()
        val mediaSource = source("media", SourceKind.MEDIASTORE, "content://media/images")
        val mediaItem = item("mediastore:7", mediaSource.id, "content://media/images/7", mediaStoreId = 7L)
        media.addSource(mediaSource)
        media.register(mediaItem)

        val router = SourceRoutingStorageGateway(saf = saf, mediaStore = media)

        assertEquals(listOf(safSource.id, mediaSource.id), router.sources().map { it.id })
        assertEquals(listOf(safItem.id), router.enumerate(safSource, null).last().items.map { it.id })
        assertEquals(listOf(mediaItem.id), router.enumerate(mediaSource, null).last().items.map { it.id })

        router.move(mediaItem, Destination(mediaSource))

        assertEquals(1, media.faultInjector.operationCount(), "a media item must move through the media gateway")
        assertEquals(0, saf.faultInjector.operationCount(), "a media item must not touch the SAF gateway")
    }

    @Test
    fun `a source scans through the read-only port without reporting truncation`() = runTest {
        val saf = FakeStorageGateway()
        val media = FakeStorageGateway()
        val mediaSource = source("media", SourceKind.MEDIASTORE, "content://media/images")
        val mediaItem = item("mediastore:7", mediaSource.id, "content://media/images/7", mediaStoreId = 7L)
        media.addSource(mediaSource)
        media.register(mediaItem)

        val page = SourceRoutingStorageGateway(saf = saf, mediaStore = media).scan(mediaSource).last()

        assertEquals(listOf(mediaItem.id), page.items.map { it.id })
        assertFalse(page.truncated, "the MediaStore catalogue has no walk cap to hit")
    }

    private fun source(id: String, kind: SourceKind, treeUri: String) = SourceRef(
        id = SourceId(id),
        treeUri = treeUri,
        displayName = id,
        access = SourceRef.Access.RW,
        kind = kind,
        itemCount = null,
        totalBytes = null,
        grantedAt = 0L,
    )

    private fun item(id: String, sourceId: SourceId, path: String, mediaStoreId: Long?) = MediaItem(
        id = ItemId(id),
        uri = path,
        path = path,
        relativePath = path.substringAfterLast('/'),
        mediaStoreId = mediaStoreId,
        mime = "image/jpeg",
        sizeBytes = 1024L,
        width = null,
        height = null,
        durationMs = null,
        dateAdded = 0L,
        dateModified = 0L,
        bucket = null,
        sourceId = sourceId,
    )
}
