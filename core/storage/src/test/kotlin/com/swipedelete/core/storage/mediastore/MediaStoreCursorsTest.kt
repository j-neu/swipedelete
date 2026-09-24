package com.swipedelete.core.storage.mediastore

import android.content.ContentValues
import android.database.ContentObserver
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.swipedelete.core.model.SourceRef
import com.swipedelete.core.storage.PageToken
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Host-JVM (Robolectric) tests for the MediaStore cursor layer: a
 * [MatrixCursor] stands in for a real `ContentResolver` cursor, so the
 * projection → [com.swipedelete.core.model.MediaItem] mapping and the paging
 * arithmetic are exercised without a device or a real media database.
 *
 * These pin the M1-T01 deliverable (spec §4.6):
 * - every projection column maps onto the right `MediaItem` field,
 * - `IS_TRASHED` / `IS_PENDING` rows never reach a deck,
 * - a non-media MIME never reaches a deck (AC-10),
 * - paging is stable: no gaps, no repeats, and a [PageToken] resumes exactly.
 */
@RunWith(RobolectricTestRunner::class)
class MediaStoreCursorsTest {

    private val sdk = 36
    private val collection = MediaStoreCollection.IMAGES
    private val collectionUri: Uri = MediaStoreCollections.contentUri(collection, sdk)
    private val source: SourceRef = MediaStoreCollections.sourceFor(collection, sdk)

    // --- Projection ---

    @Test
    fun `spec 4 6 projection is requested verbatim`() {
        assertEquals(
            listOf(
                "_id",
                "relative_path",
                "_size",
                "date_added",
                "date_modified",
                "mime_type",
                "width",
                "height",
                "duration",
                "bucket_display_name",
                "is_trashed",
                "is_pending",
                "owner_package_name",
            ),
            MediaStoreProjection.SPEC_4_6.toList(),
        )
        // A display name is not in §4.6 but a deck cannot label an item without
        // one, so the deck projection extends §4.6 rather than replacing it.
        assertEquals(
            MediaStoreProjection.SPEC_4_6.toList() + "_display_name",
            MediaStoreProjection.DECK.toList(),
        )
    }

    // --- Mapping ---

    @Test
    fun `every projection column maps onto the right media item field`() = runTest {
        val gateway = gateway(
            rows = listOf(
                row(
                    id = 42L,
                    relativePath = "Pictures/Screenshots/",
                    displayName = "shot.png",
                    size = 2048L,
                    dateAdded = 1_600_000_000L,
                    dateModified = 1_600_000_500L,
                    mime = "image/png",
                    width = 1080,
                    height = 1920,
                    duration = null,
                    bucket = "Screenshots",
                    owner = "com.example.camera",
                ),
            ),
        )

        val item = gateway.enumerate(source, null).first().items.single()

        assertEquals(42L, item.mediaStoreId)
        assertEquals("mediastore:42", item.id.value)
        assertEquals(
            Uri.withAppendedPath(collectionUri, "42").toString(),
            item.uri,
        )
        assertEquals("Pictures/Screenshots/shot.png", item.relativePath)
        assertEquals("Pictures/Screenshots/shot.png", item.path)
        assertEquals("image/png", item.mime)
        assertEquals(2048L, item.sizeBytes)
        assertEquals(1080, item.width)
        assertEquals(1920, item.height)
        assertEquals(null, item.durationMs)
        assertEquals(1_600_000_000L, item.dateAdded)
        assertEquals(1_600_000_500L, item.dateModified)
        assertEquals("Screenshots", item.bucket)
        assertEquals(source.id, item.sourceId)
    }

    @Test
    fun `zero width height and duration are reported as unknown rather than zero`() = runTest {
        val item = gateway(
            rows = listOf(row(id = 7L, mime = "audio/mpeg", width = 0, height = 0, duration = 0)),
        ).enumerate(source, null).first().items.single()

        assertNull(item.width)
        assertNull(item.height)
        assertNull(item.durationMs)
    }

    // --- IS_TRASHED / IS_PENDING exclusion ---

    @Test
    fun `pending and trashed rows are excluded from the deck`() = runTest {
        val gateway = gateway(
            rows = listOf(
                row(id = 1L, mime = "image/jpeg"),
                row(id = 2L, mime = "image/jpeg", isPending = 1),
                row(id = 3L, mime = "image/jpeg", isTrashed = 1),
                row(id = 4L, mime = "image/jpeg", isPending = 1, isTrashed = 1),
            ),
        )

        val ids = gateway.enumerate(source, null).toList().flatMap { it.items }.map { it.mediaStoreId }

        assertEquals(listOf(1L), ids)
    }

    // --- AC-10 ---

    @Test
    fun `a non-media mime row never reaches a deck`() = runTest {
        val gateway = gateway(
            rows = listOf(
                row(id = 1L, mime = "image/jpeg"),
                row(id = 2L, mime = "application/pdf"),
                row(id = 3L, mime = "application/vnd.android.package-archive"),
                row(id = 4L, mime = "video/mp4"),
                row(id = 5L, mime = "audio/mpeg"),
            ),
        )

        val ids = gateway.enumerate(source, null).toList().flatMap { it.items }.map { it.mediaStoreId }

        assertEquals(listOf(1L, 4L, 5L), ids)
    }

    @Test
    fun `sources are only the media collections and never MediaStore Files`() = runTest {
        val sources = gateway(rows = emptyList()).sources()

        assertEquals(
            listOf(
                MediaStoreCollection.IMAGES,
                MediaStoreCollection.VIDEO,
                MediaStoreCollection.AUDIO,
            ),
            sources.map { MediaStoreCollections.collectionFor(it, sdk) },
        )
        assertTrue(
            sources.none { it.treeUri.contains("external/file", ignoreCase = true) },
            "MediaStore.Files must never be enumerated (AC-10): $sources",
        )
    }

    // --- Paging ---

    @Test
    fun `paging is stable with no gaps and no repeats`() = runTest {
        val rows = (1L..5L).map { row(id = it, mime = "image/jpeg") }
        val gateway = gateway(rows = rows, pageSize = 2)

        val pages = gateway.enumerate(source, null).toList()

        assertEquals(listOf(2, 2, 1), pages.map { it.items.size })
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), pages.flatMap { it.items }.map { it.mediaStoreId })
        assertNull(pages.last().next, "the final page carries no continuation token")
        assertNotNull(pages.first().next)

        // Re-running the same enumeration yields the same ids in the same order.
        assertEquals(
            listOf(1L, 2L, 3L, 4L, 5L),
            gateway.enumerate(source, null).toList().flatMap { it.items }.map { it.mediaStoreId },
        )
    }

    @Test
    fun `a page token resumes exactly where the previous page stopped`() = runTest {
        val rows = (1L..5L).map { row(id = it, mime = "image/jpeg") }
        val gateway = gateway(rows = rows, pageSize = 2)

        val first = gateway.enumerate(source, null).first()
        assertEquals(listOf(1L, 2L), first.items.map { it.mediaStoreId })

        val resumed = gateway.enumerate(source, first.next).toList()

        assertEquals(listOf(2, 1), resumed.map { it.items.size })
        assertEquals(listOf(3L, 4L, 5L), resumed.flatMap { it.items }.map { it.mediaStoreId })
    }

    @Test
    fun `a page token from another source is rejected rather than mis-resumed`() = runTest {
        val gateway = gateway(rows = (1L..5L).map { row(id = it, mime = "image/jpeg") }, pageSize = 1)
        val token = gateway.enumerate(source, null).first().next
        val videoSource = MediaStoreCollections.sourceFor(MediaStoreCollection.VIDEO, sdk)

        val failure = runCatching { gateway.enumerate(videoSource, token).first() }
        assertTrue(failure.isFailure, "resuming a token on another source must fail, not mis-stitch pages")
    }

    // --- Fixture plumbing ---

    private fun gateway(rows: List<Row>, pageSize: Int = 100): MediaStoreStorageGateway =
        MediaStoreStorageGateway(
            context = ApplicationProvider.getApplicationContext(),
            access = FakeMediaStoreAccess(collectionUri, rows),
            pageSize = pageSize,
            sdkInt = sdk,
        )

    private fun row(
        id: Long,
        relativePath: String = "Pictures/",
        displayName: String = "file$id.jpg",
        size: Long = 1024L,
        dateAdded: Long = 0L,
        dateModified: Long = 0L,
        mime: String = "image/jpeg",
        width: Int? = 0,
        height: Int? = 0,
        duration: Long? = null,
        bucket: String? = null,
        owner: String? = null,
        isTrashed: Int = 0,
        isPending: Int = 0,
    ): Row = Row(
        id = id,
        relativePath = relativePath,
        displayName = displayName,
        size = size,
        dateAdded = dateAdded,
        dateModified = dateModified,
        mime = mime,
        width = width,
        height = height,
        duration = duration,
        bucket = bucket,
        owner = owner,
        isTrashed = isTrashed,
        isPending = isPending,
    )

    private data class Row(
        val id: Long,
        val relativePath: String?,
        val displayName: String?,
        val size: Long,
        val dateAdded: Long,
        val dateModified: Long,
        val mime: String,
        val width: Int?,
        val height: Int?,
        val duration: Long?,
        val bucket: String?,
        val owner: String?,
        val isTrashed: Int,
        val isPending: Int,
    )

    /** A [MediaStoreAccess] backed by in-memory rows, applying limit/offset itself. */
    private class FakeMediaStoreAccess(
        private val expectedUri: Uri,
        private val rows: List<Row>,
    ) : MediaStoreAccess {

        override fun query(
            uri: Uri,
            projection: Array<String>,
            selection: String?,
            selectionArgs: Array<String>?,
            sortOrder: String?,
            offset: Int,
            limit: Int,
        ): Cursor {
            check(uri == expectedUri) { "unexpected collection: $uri" }
            val window = rows.drop(offset).take(limit)
            val cursor = MatrixCursor(projection)
            window.forEach { row ->
                cursor.addRow(
                    projection.map { column ->
                        when (column) {
                            "_id" -> row.id
                            "relative_path" -> row.relativePath
                            "_display_name" -> row.displayName
                            "_size" -> row.size
                            "date_added" -> row.dateAdded
                            "date_modified" -> row.dateModified
                            "mime_type" -> row.mime
                            "width" -> row.width
                            "height" -> row.height
                            "duration" -> row.duration
                            "bucket_display_name" -> row.bucket
                            "owner_package_name" -> row.owner
                            "is_trashed" -> row.isTrashed
                            "is_pending" -> row.isPending
                            else -> null
                        }
                    },
                )
            }
            return cursor
        }

        override fun openStream(uri: Uri): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

        override fun update(
            uri: Uri,
            values: ContentValues,
            selection: String?,
            selectionArgs: Array<String>?,
        ): Int = 0

        override fun insert(uri: Uri, values: ContentValues): Uri? = null

        override fun registerContentObserver(uri: Uri, observer: ContentObserver) = Unit

        override fun unregisterContentObserver(observer: ContentObserver) = Unit
    }
}
