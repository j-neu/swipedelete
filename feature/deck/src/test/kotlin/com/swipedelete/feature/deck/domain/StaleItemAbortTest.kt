package com.swipedelete.feature.deck.domain

import com.swipedelete.core.model.MediaItem
import com.swipedelete.core.storage.Metadata
import com.swipedelete.core.storage.fake.FakeStorageGateway
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AC-8: the deck is a frozen snapshot. A file that changes on disk between
 * build and presentation must abort that item with a visible message instead of
 * letting a stale decision act on it.
 */
class StaleItemAbortTest {

    private val base = mediaItem(
        id = "shot",
        path = "content://tree/s/Pictures/Screenshots/IMG_1.png",
        bucket = "Screenshots",
        relativePath = "Pictures/Screenshots",
        mime = "image/png",
        sizeBytes = 4_404_019L,
        dateAdded = 1_000L,
        dateModified = 1_000L,
    ).copy(hash = "sha-original")

    private fun metadataOf(item: MediaItem) = Metadata(
        sizeBytes = item.sizeBytes,
        mime = item.mime,
        lastModified = item.dateModified,
        sha256 = item.hash,
        width = item.width,
        height = item.height,
        durationMs = item.durationMs,
    )

    @Test
    fun `an item that did not change is not stale`() {
        assertNull(staleReason(base, metadataOf(base)))
    }

    @Test
    fun `a size change is stale and names the change`() {
        val reason = staleReason(base, metadataOf(base).copy(sizeBytes = base.sizeBytes + 1L))

        assertNotNull(reason)
        assertTrue(reason.isNotBlank(), "the abort must carry a visible message")
        assertTrue(reason.contains("size"), reason)
    }

    @Test
    fun `a modification-time change is stale`() {
        val reason = staleReason(base, metadataOf(base).copy(lastModified = base.dateModified + 1L))

        assertNotNull(reason)
        assertTrue(reason.contains("modified", ignoreCase = true), reason)
    }

    @Test
    fun `a mime change is stale`() {
        val reason = staleReason(base, metadataOf(base).copy(mime = "video/mp4"))

        assertNotNull(reason)
        assertTrue(reason.contains("type", ignoreCase = true), reason)
    }

    @Test
    fun `a content hash change is stale when both hashes are known`() {
        val reason = staleReason(base, metadataOf(base).copy(sha256 = "sha-replaced"))

        assertNotNull(reason)
        assertTrue(reason.contains("contents", ignoreCase = true), reason)
    }

    @Test
    fun `an unobserved hash is not treated as a change`() {
        assertNull(staleReason(base, metadataOf(base).copy(sha256 = null)))
        assertNull(staleReason(base.copy(hash = null), metadataOf(base).copy(sha256 = "sha-replaced")))
    }

    @Test
    fun `a file rewritten after the deck was built aborts at presentation`() = runTest {
        val source = sourceRef("s")
        val original = mediaItem(
            id = "a",
            path = "content://tree/s/a.jpg",
            mime = "image/jpeg",
            sizeBytes = 1_000L,
            dateModified = 5L,
            sourceId = source.id,
        )
        val gateway = FakeStorageGateway().apply {
            addSource(source)
            register(original)
        }
        val builder = DeckBuilder({ FakeScanPort(page(listOf(original))) }, gateway, FixedClock(0L))

        val built = builder.build(DeckQuery.Folder(source.id)).items.single()
        assertNull(staleReason(built, gateway.metadata(built)), "nothing changed yet")

        // Another app rewrites the file under us.
        gateway.register(built.copy(sizeBytes = 2_000L))

        val reason = staleReason(built, gateway.metadata(built))

        assertNotNull(reason, "the presenter must abort the item, not act on stale data")
        assertTrue(reason.contains("size"), reason)
    }
}
