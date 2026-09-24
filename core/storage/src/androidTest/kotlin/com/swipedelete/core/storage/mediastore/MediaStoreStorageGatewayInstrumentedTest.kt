package com.swipedelete.core.storage.mediastore

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.swipedelete.core.common.Clock
import com.swipedelete.core.common.Sha256Hasher
import com.swipedelete.core.model.BatchId
import com.swipedelete.core.model.ItemId
import com.swipedelete.core.model.JournalEntry
import com.swipedelete.core.model.MediaItem
import com.swipedelete.core.model.SourceRef
import com.swipedelete.core.storage.Destination
import com.swipedelete.core.storage.Metadata
import com.swipedelete.core.storage.MoveResult
import com.swipedelete.core.storage.Page
import com.swipedelete.core.storage.PageToken
import com.swipedelete.core.storage.QuarantineResult
import com.swipedelete.core.storage.QuarantineStorageGateway
import com.swipedelete.core.storage.StorageGateway
import com.swipedelete.core.storage.StorageGatewayContractTest
import com.swipedelete.core.storage.quarantine.QuarantineStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The frozen [StorageGatewayContractTest] run against the **real** MediaStore
 * path on a device or emulator, plus a MediaStore-specific identity assertion.
 *
 * **This test is written but has NOT been run**: there is no booted device in
 * the Task 29 session. The device pass belongs to the M1 device/CI task and
 * runs `.\gradlew.bat :core:storage:connectedDebugAndroidTest`.
 *
 * ### Why there is a [ContractGateway] adapter
 * The frozen contract addresses items through synthetic `fake://primary/a.gif`
 * sources and mints ids as `"sourceId:name"`. This gateway's mandated identity
 * is the MediaStore row — `MediaItem.id = "mediastore:<_ID>"`,
 * `mediaStoreId = _ID` — and it has no synthetic-source mode (a `SourceRef` must
 * name a real media collection). [ContractGateway] is the same test glue as
 * `SafStorageGatewayInstrumentedTest.RemappedAccess`: it scopes the real image
 * catalogue to the fixture's source and presents ids in the fixture's
 * convention, while `enumerate`, `metadata`, `openStream`, `move` and the
 * quarantine path all run the genuine [MediaStoreStorageGateway] against real
 * rows. AC-15 is armed through [CancellingMediaStoreAccess], the analogue of the
 * SAF suite's cancelling stream open.
 */
@RunWith(AndroidJUnit4::class)
class MediaStoreStorageGatewayInstrumentedTest : StorageGatewayContractTest() {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val resolver: ContentResolver = context.contentResolver
    private val sdkInt: Int = Build.VERSION.SDK_INT
    private val imagesUri: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    private val imagesSource: SourceRef = MediaStoreCollections.sourceFor(MediaStoreCollection.IMAGES, sdkInt)

    private var serial = 0

    override fun newGateway(items: List<MediaItem>): StorageGateway {
        clearTestRows()
        items.forEach { insertItem(it) }

        val access = CancellingMediaStoreAccess(ContentResolverMediaStoreQuery(context))
        val concrete = MediaStoreStorageGateway(context = context, access = access, quarantineStore = null)
        val store = QuarantineStore(
            root = File(context.cacheDir, "mediastore-quarantine/${serial++}"),
            gateway = concrete,
            hasher = Sha256Hasher(),
            clock = object : Clock {
                override fun nowMillis(): Long = System.currentTimeMillis()
            },
        )
        val decorated = QuarantineStorageGateway(concrete, store)
        return ContractGateway(decorated, imagesSource, onArm = access::arm)
    }

    override fun destinationWith(existingName: String): Destination {
        insertItem(item(name = existingName, source = DESTINATION_SOURCE))
        return Destination(DESTINATION_SOURCE)
    }

    override fun newFileAt(path: String) {
        val (source, name) = sourceAndNameFor(path)
        insertItem(item(name = name, source = source))
    }

    override fun fileExists(path: String): Boolean =
        if (path.startsWith("fake://")) {
            catalogue().any { it.relativePath == logicalPath(path) }
        } else {
            File(path).exists()
        }

    override fun quarantinePathFor(entry: JournalEntry): String =
        entry.toUri ?: error("quarantine entry has no toUri")

    /**
     * AC-15: the next stream open throws a [CancellationException], which is how
     * a cancelled scope surfaces at the copy boundary. The store rethrows it
     * untouched and the original row is never deleted.
     */
    override suspend fun armCancellation(gateway: StorageGateway) {
        (gateway as ContractGateway).armCancellation()
    }

    // --- MediaStore-specific identity ---

    @Test
    fun enumerateMintsMediaStoreIdentity() {
        newGateway(listOf(item(name = "a.gif")))
        val item = catalogue().first { it.relativePath?.startsWith("$ROOT/primary/") == true }

        assertTrue(item.mediaStoreId != null)
        assertEquals("mediastore:${item.mediaStoreId}", item.id.value)
    }

    // --- Fixture plumbing ---

    private fun sourceScope(source: SourceRef): String = when (source.id) {
        PRIMARY_SOURCE.id -> "$ROOT/primary/"
        DESTINATION_SOURCE.id -> "$ROOT/export/"
        else -> "$ROOT/${source.id.value}/"
    }

    private fun logicalPath(fakePath: String): String {
        val (source, name) = sourceAndNameFor(fakePath)
        return sourceScope(source) + name
    }

    private fun sourceAndNameFor(fakePath: String): Pair<SourceRef, String> {
        val name = fakePath.substringAfterLast('/')
        return if (fakePath.startsWith("${DESTINATION_SOURCE.treeUri}/")) {
            DESTINATION_SOURCE to name
        } else {
            PRIMARY_SOURCE to name
        }
    }

    private fun insertItem(item: MediaItem) {
        val scope = sourceScope(
            if (item.sourceId == DESTINATION_SOURCE.id) DESTINATION_SOURCE else PRIMARY_SOURCE,
        )
        val name = item.relativePath?.substringAfterLast('/') ?: item.uri.substringAfterLast('/')
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, item.mime)
            if (sdkInt >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, scope)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(imagesUri, values) ?: error("MediaStore refused $name")
        resolver.openOutputStream(uri)?.use { it.write(ByteArray(item.sizeBytes.coerceAtMost(4096L).toInt())) }
        if (sdkInt >= Build.VERSION_CODES.Q) {
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }
    }

    private fun clearTestRows() {
        if (sdkInt < Build.VERSION_CODES.Q) return
        runCatching {
            resolver.delete(imagesUri, "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("$ROOT%"))
        }
    }

    /** The real catalogue rows, as the concrete gateway sees them. */
    private fun catalogue(): List<MediaItem> = runBlocking {
        MediaStoreStorageGateway(context, sdkInt = sdkInt)
            .enumerate(imagesSource, null)
            .toList()
            .flatMap { it.items }
    }

    private companion object {
        const val ROOT = "Pictures/SwipeDeleteM1T01"
    }

    /**
     * Test glue: scopes the real image catalogue to the frozen contract's
     * synthetic source and presents ids in its `"sourceId:name"` convention.
     * Every operation forwards to the genuine decorated MediaStore stack; the
     * only translation is identity and scoping.
     */
    private class ContractGateway(
        private val delegate: StorageGateway,
        private val imagesSource: SourceRef,
        private val onArm: () -> Unit,
    ) : StorageGateway by delegate {

        fun armCancellation() = onArm()

        override fun enumerate(source: SourceRef, page: PageToken?): Flow<Page<MediaItem>> = flow {
            val scope = scopeOf(source)
            delegate.enumerate(imagesSource, page).collect { delegatePage ->
                val scoped = delegatePage.items
                    .filter { it.relativePath?.startsWith(scope) == true }
                    .map { catalogueItem ->
                        val name = catalogueItem.relativePath!!.removePrefix(scope)
                        catalogueItem.copy(id = ItemId("${source.id.value}:$name"))
                    }
                emit(Page(scoped, delegatePage.next))
            }
        }

        override suspend fun move(item: MediaItem, destination: Destination): MoveResult =
            delegate.move(
                resolve(item),
                Destination(source = imagesSource, relativePath = scopeOf(destination.source).trimEnd('/')),
            )

        override suspend fun quarantine(items: List<MediaItem>, batch: BatchId): QuarantineResult =
            delegate.quarantine(items.map { resolve(it) }, batch)

        override suspend fun metadata(item: MediaItem): Metadata = delegate.metadata(resolve(item))

        private suspend fun resolve(item: MediaItem): MediaItem {
            val scope = scopeOf(StorageGatewayContractTest.PRIMARY_SOURCE)
            val name = item.relativePath ?: item.uri.substringAfterLast('/')
            return delegate.enumerate(imagesSource, null).toList()
                .flatMap { it.items }
                .firstOrNull { it.relativePath == scope + name }
                ?: item
        }

        private fun scopeOf(source: SourceRef): String = when (source.id) {
            StorageGatewayContractTest.PRIMARY_SOURCE.id -> "$ROOT/primary/"
            StorageGatewayContractTest.DESTINATION_SOURCE.id -> "$ROOT/export/"
            else -> "$ROOT/${source.id.value}/"
        }

        private companion object {
            const val ROOT = "Pictures/SwipeDeleteM1T01"
        }
    }

    /** A [MediaStoreAccess] that can arm a one-shot stream cancellation (AC-15). */
    private class CancellingMediaStoreAccess(
        private val delegate: MediaStoreAccess,
    ) : MediaStoreAccess {

        private val cancelNextOpen = AtomicBoolean(false)

        fun arm() = cancelNextOpen.set(true)

        override fun query(
            uri: Uri,
            projection: Array<String>,
            selection: String?,
            selectionArgs: Array<String>?,
            sortOrder: String?,
            offset: Int,
            limit: Int,
        ): Cursor? = delegate.query(uri, projection, selection, selectionArgs, sortOrder, offset, limit)

        override fun openStream(uri: Uri): InputStream {
            if (cancelNextOpen.getAndSet(false)) throw CancellationException("armed cancellation")
            return delegate.openStream(uri)
        }

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int =
            delegate.delete(uri, selection, selectionArgs)

        override fun update(uri: Uri, values: ContentValues, selection: String?, selectionArgs: Array<String>?): Int =
            delegate.update(uri, values, selection, selectionArgs)

        override fun insert(uri: Uri, values: ContentValues): Uri? = delegate.insert(uri, values)

        override fun registerContentObserver(uri: Uri, observer: ContentObserver) =
            delegate.registerContentObserver(uri, observer)

        override fun unregisterContentObserver(observer: ContentObserver) =
            delegate.unregisterContentObserver(observer)
    }
}
