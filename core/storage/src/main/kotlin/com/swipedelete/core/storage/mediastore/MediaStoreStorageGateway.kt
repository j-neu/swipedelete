package com.swipedelete.core.storage.mediastore

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.MediaStore
import com.swipedelete.core.model.BatchId
import com.swipedelete.core.model.Capacity
import com.swipedelete.core.model.ItemId
import com.swipedelete.core.model.JournalEntry
import com.swipedelete.core.model.MediaItem
import com.swipedelete.core.model.SourceId
import com.swipedelete.core.model.SourceKind
import com.swipedelete.core.model.SourceRef
import com.swipedelete.core.storage.ChangeEvent
import com.swipedelete.core.storage.DeleteResult
import com.swipedelete.core.storage.Destination
import com.swipedelete.core.storage.Failure
import com.swipedelete.core.storage.ItemLocator
import com.swipedelete.core.storage.Metadata
import com.swipedelete.core.storage.MoveResult
import com.swipedelete.core.storage.Page
import com.swipedelete.core.storage.PageToken
import com.swipedelete.core.storage.QuarantineResult
import com.swipedelete.core.storage.RestoreResult
import com.swipedelete.core.storage.StorageGateway
import com.swipedelete.core.storage.quarantine.QuarantineStore
import com.swipedelete.core.storage.saf.SafStorageGateway
import com.swipedelete.core.storage.scan.ScanPage
import com.swipedelete.core.storage.scan.SourceScanPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * Tier 1 storage: the **media catalogue** gateway (spec §4.6).
 *
 * Media is never walked as files. `MediaStore` already holds an indexed
 * catalogue — `_ID`, `RELATIVE_PATH`, `SIZE`, `DATE_ADDED`, `DATE_MODIFIED`,
 * `MIME_TYPE`, `WIDTH`, `HEIGHT`, `DURATION`, `BUCKET_DISPLAY_NAME`,
 * `IS_TRASHED`, `IS_PENDING`, `OWNER_PACKAGE_NAME` — and querying it with
 * `ContentResolver` cursors is what makes a 60,000-item library usable. This is
 * simultaneously the performance answer and the compliance answer: keeping
 * media on MediaStore is what makes the All Files Access declaration defensible
 * (§4.2 condition 2).
 *
 * ### AC-10 is enforced structurally
 * This gateway is the **media side** of the split. [sources] offers only the
 * images / video / audio collections, `MediaStore.Files` is never queried, and
 * [MediaStoreCursorMapper] drops any row whose `MIME_TYPE` is not media. There
 * is no path through this class that serves a non-media file, and no path
 * through the raw gateway (Tier 2) that serves media.
 *
 * ### Identity
 * A catalogue row's `_ID` is the identity: `MediaItem.mediaStoreId = _ID` and
 * `MediaItem.id = "mediastore:<_ID>"`. `MediaItem.uri` is the row's content
 * URI; `MediaItem.path` / `relativePath` are the logical
 * `<RELATIVE_PATH><_display_name>`, never a filesystem handle (V4: on API 30+
 * a raw-path rename of another app's media is not a permitted route).
 *
 * ### Paging
 * [enumerate] asks for fixed-size windows ordered by `_id`, and every
 * non-final page carries a serialised [PageToken] (`sourceId`, collection,
 * offset). A token is validated against the source it is resumed on, so pages
 * cannot be stitched across collections. The mapper excludes `IS_TRASHED` and
 * `IS_PENDING` rows, and the selection is narrowed to match on API 29+.
 *
 * ### Mutation
 * [move] is the `RELATIVE_PATH` move and requires API 30 (V4). [quarantine] /
 * [restore] delegate to a **caller-supplied** [QuarantineStore]: production
 * wiring is `ProtectedRoots → Journaling → Quarantine → Concrete`, so this
 * concrete gateway must not own a second quarantine root (same rule as
 * [SafStorageGateway]). [deletePermanently] is the raw row delete used by the
 * quarantine store; the consent ladder (§4.7 `createDeleteRequest`) lives above
 * this gateway.
 *
 * @param access the `ContentResolver` seam; [ContentResolverMediaStoreQuery] in
 *   production, an in-memory fake in host tests.
 * @param pageSize rows per page.
 * @param sdkInt the API level the projection/selection are built for.
 */
class MediaStoreStorageGateway(
    context: Context,
    private val access: MediaStoreAccess = ContentResolverMediaStoreQuery(context),
    private val quarantineStore: QuarantineStore? = null,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : StorageGateway, ItemLocator, SourceScanPort {

    init {
        require(pageSize > 0) { "pageSize must be positive, was $pageSize" }
    }

    /** The volume [capacity] reports on, used only for the space pre-flight. */
    private val capacityProbe: java.io.File = context.getExternalFilesDir(null) ?: context.filesDir

    // --- StorageGateway ---

    override suspend fun sources(): List<SourceRef> =
        MediaStoreCollections.ALL.map { MediaStoreCollections.sourceFor(it, sdkInt) }

    override fun enumerate(source: SourceRef, page: PageToken?): Flow<Page<MediaItem>> = flow {
        val collection = MediaStoreCollections.collectionFor(source, sdkInt)
            ?: throw IllegalArgumentException("not a MediaStore source: ${source.treeUri}")
        val collectionUri = MediaStoreCollections.contentUri(collection, sdkInt)
        val projection = MediaStoreProjection.projectionFor(sdkInt)
        val selection = MediaStoreProjection.selectionFor(sdkInt)

        var offset = page?.let { decodeToken(it, source, collection) } ?: 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val (items, rows) = access
                .query(collectionUri, projection, selection, null, MediaStoreProjection.SORT_ORDER, offset, pageSize)
                .use { cursor ->
                    if (cursor == null) return@use emptyList<MediaItem>() to 0
                    val mapped = ArrayList<MediaItem>(cursor.count)
                    var read = 0
                    while (cursor.moveToNext()) {
                        read++
                        MediaStoreCursorMapper.item(cursor, source, collectionUri)?.let { mapped += it }
                    }
                    mapped to read
                }

            if (rows == 0) break
            val lastPage = rows < pageSize
            // Never emit an empty page: a window of only excluded rows advances
            // the offset but produces no deck item.
            if (items.isNotEmpty()) {
                emit(Page(items, if (lastPage) null else encodeToken(source, collection, offset + rows)))
            }
            if (lastPage) break
            offset += rows
        }
    }

    /**
     * The MediaStore catalogue is an index, not a filesystem walk, so a scan is
     * exactly [enumerate] with cumulative progress attached; it is never
     * truncated (there is no depth/entry cap to hit). This makes a media source
     * usable through the same read-only [SourceScanPort] the deck scans a SAF
     * source with (Task 30).
     */
    override fun scan(source: SourceRef, page: PageToken?): Flow<ScanPage> {
        var scanned = 0
        return enumerate(source, page).map { paged ->
            scanned += paged.items.size
            ScanPage(items = paged.items, next = paged.next, truncated = false, scanned = scanned)
        }
    }

    override suspend fun metadata(item: MediaItem): Metadata {
        val collection = collectionForItem(item)
            ?: throw FileNotFoundException("item ${item.id.value} names no MediaStore collection")
        val id = item.mediaStoreId
            ?: throw FileNotFoundException("item ${item.id.value} has no mediaStoreId")
        val collectionUri = MediaStoreCollections.contentUri(collection, sdkInt)
        val mapped = queryRow(collectionUri, id)
            ?: throw FileNotFoundException("MediaStore row $id is gone")
        return Metadata(
            sizeBytes = mapped.sizeBytes,
            mime = mapped.mime,
            lastModified = mapped.dateModified,
            sha256 = null,
            width = mapped.width,
            height = mapped.height,
            durationMs = mapped.durationMs,
        )
    }

    override fun openStream(item: MediaItem): InputStream = access.openStream(Uri.parse(item.uri))

    /**
     * The MediaStore move: rewriting the row's `RELATIVE_PATH` and
     * `_display_name`. It requires API 30 and a collision-safe rename (` (1)`,
     * ` (2)`…), so a move never overwrites. Never throws except a
     * [CancellationException].
     */
    override suspend fun move(item: MediaItem, destination: Destination): MoveResult = try {
        doMove(item, destination)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        MoveResult.Failed(error.message ?: "move_failed")
    }

    override suspend fun quarantine(items: List<MediaItem>, batch: BatchId): QuarantineResult {
        val store = quarantineStore
            ?: return QuarantineResult.Partial(
                entries = emptyList(),
                failures = items.map { Failure(it.id, QUARANTINE_NOT_CONFIGURED) },
            )
        store.preflight(items)?.let { return it }

        val entries = ArrayList<JournalEntry>(items.size)
        val failures = ArrayList<Failure>()
        items.forEach { item ->
            try {
                entries += store.store(item, batch)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failures += Failure(item.id, error.message ?: "quarantine_failed", error::class.simpleName)
            }
        }
        return if (failures.isEmpty()) {
            QuarantineResult.Quarantined(batch, entries)
        } else {
            QuarantineResult.Partial(entries, failures)
        }
    }

    override suspend fun restore(entry: JournalEntry): RestoreResult =
        quarantineStore?.restore(entry)
            ?: RestoreResult.Conflict(entry.fromUri, QUARANTINE_NOT_CONFIGURED)

    /**
     * The concrete row delete behind the quarantine store. The system-consent
     * ladder (§4.7: `createDeleteRequest`, ≤ 2000 URIs) lives in the staging
     * layer above this gateway; this is the primitive the app already holds a
     * scoped grant for.
     */
    override suspend fun deletePermanently(items: List<MediaItem>): DeleteResult {
        val failures = ArrayList<Failure>()
        var deleted = 0
        for (item in items) {
            val uri = runCatching { Uri.parse(item.uri) }.getOrNull()
            if (uri == null) {
                failures += Failure(item.id, "invalid_uri")
                continue
            }
            val rows = try {
                access.delete(uri, null, null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                -1
            }
            if (rows > 0) deleted++ else failures += Failure(item.id, "delete_refused")
        }
        return if (failures.isEmpty()) DeleteResult.Deleted(deleted) else DeleteResult.Partial(deleted, failures)
    }

    override suspend fun capacity(): Capacity = try {
        val stats = StatFs(capacityProbe.absolutePath)
        val total = stats.totalBytes
        val free = stats.availableBytes
        Capacity(totalBytes = total, usedBytes = total - free, freeBytes = free, reclaimableBytes = 0L)
    } catch (error: Throwable) {
        Capacity(totalBytes = 0L, usedBytes = 0L, freeBytes = 0L, reclaimableBytes = 0L)
    }

    /**
     * Incremental change notifications via a [ContentObserver] on the
     * collection. A changed row URI is resolved to a mapped item
     * ([ChangeEvent.Added] when it appears, [ChangeEvent.Modified] when the row
     * still exists — MediaStore does not say which) or to
     * [ChangeEvent.Removed] when the row is gone. A batch delete performed
     * through the consent dialog fires here, which is why the deck must tolerate
     * a row disappearing mid-session (AC-8).
     */
    override fun watch(source: SourceRef): Flow<ChangeEvent> = callbackFlow {
        val collection = MediaStoreCollections.collectionFor(source, sdkInt)
        if (collection == null) {
            close(IllegalArgumentException("not a MediaStore source: ${source.treeUri}"))
            return@callbackFlow
        }
        val collectionUri = MediaStoreCollections.contentUri(collection, sdkInt)
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, changedUri: Uri?) {
                resolveChange(source, collection, collectionUri, changedUri)?.let { trySend(it) }
            }
        }
        access.registerContentObserver(collectionUri, observer)
        awaitClose { access.unregisterContentObserver(observer) }
    }

    /**
     * [ItemLocator]: a catalogue item's `uri` is its real content URI, so
     * existence is a row lookup by `_ID`. Used by startup reconciliation rather
     * than a URI-shape heuristic.
     */
    override suspend fun exists(uri: String): Boolean {
        if (uri.isBlank()) return false
        val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return false
        val id = runCatching { ContentUris.parseId(parsed) }.getOrNull() ?: return false
        val collection = MediaStoreCollections.collectionForUri(parsed.toString(), sdkInt) ?: return false
        val collectionUri = MediaStoreCollections.contentUri(collection, sdkInt)
        val cursor = access.query(
            collectionUri,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns._ID} = ?",
            arrayOf(id.toString()),
            MediaStoreProjection.SORT_ORDER,
            0,
            1,
        )
        return cursor.use { it != null && it.moveToFirst() }
    }

    // --- Internals ---

    private fun doMove(item: MediaItem, destination: Destination): MoveResult {
        if (sdkInt < Build.VERSION_CODES.R) return MoveResult.Failed(MOVE_REQUIRES_API_30)
        val collection = collectionForItem(item) ?: return MoveResult.Failed("unknown_source")
        val destinationCollection = MediaStoreCollections.collectionFor(destination.source, sdkInt)
            ?: return MoveResult.Failed("destination_not_a_mediastore_collection")
        val id = item.mediaStoreId ?: return MoveResult.Failed("item_has_no_mediastore_id")
        val name = item.relativePath?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: return MoveResult.Failed("item_has_no_display_name")

        val destinationDirectory = destination.relativePath
            ?.trim('/')
            ?.takeIf { it.isNotEmpty() }
            ?.let { "$it/" }
            .orEmpty()
        val destinationUri = MediaStoreCollections.contentUri(destinationCollection, sdkInt)
        val names = displayNamesIn(destinationUri, destinationDirectory)
        val collision = name in names
        val resolvedName = if (collision) SafStorageGateway.freeName(names, name) else name

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.RELATIVE_PATH, destinationDirectory)
            put(MediaStore.MediaColumns.DISPLAY_NAME, resolvedName)
        }
        val rowUri = ContentUris.withAppendedId(MediaStoreCollections.contentUri(collection, sdkInt), id)
        val updated = access.update(rowUri, values, null, null)
        if (updated <= 0) return MoveResult.Failed("move_refused")

        val resolvedPath = destinationDirectory + resolvedName
        val moved = item.copy(
            relativePath = resolvedPath,
            path = resolvedPath,
            sourceId = destination.source.id,
        )
        return if (collision) MoveResult.NameCollision(moved) else MoveResult.Moved(moved)
    }

    /** Display names already present in [directory] of [collectionUri]. */
    private fun displayNamesIn(collectionUri: Uri, directory: String): Set<String> {
        val cursor = access.query(
            collectionUri,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf(directory),
            MediaStoreProjection.SORT_ORDER,
            0,
            ROW_SCAN_LIMIT,
        )
        return cursor.use { rows ->
            if (rows == null) return emptySet()
            val names = HashSet<String>()
            val column = rows.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            while (rows.moveToNext()) {
                if (column >= 0 && !rows.isNull(column)) rows.getString(column)?.let { names += it }
            }
            names
        }
    }

    private fun queryRow(collectionUri: Uri, id: Long): MediaItem? = access
        .query(
            collectionUri,
            MediaStoreProjection.projectionFor(sdkInt),
            "${MediaStore.MediaColumns._ID} = ?",
            arrayOf(id.toString()),
            MediaStoreProjection.SORT_ORDER,
            0,
            1,
        )
        .use { cursor ->
            if (cursor == null || !cursor.moveToFirst()) return@use null
            val source = sourceForCollectionUri(collectionUri)
            MediaStoreCursorMapper.item(cursor, source, collectionUri)
        }

    private fun resolveChange(
        source: SourceRef,
        collection: MediaStoreCollection,
        collectionUri: Uri,
        changedUri: Uri?,
    ): ChangeEvent? {
        val id = changedUri?.let { runCatching { ContentUris.parseId(it) }.getOrNull() } ?: return null
        val mapped = queryRow(collectionUri, id)
        return if (mapped != null) ChangeEvent.Modified(mapped) else ChangeEvent.Removed(ItemId("mediastore:$id"))
    }

    private fun collectionForItem(item: MediaItem): MediaStoreCollection? =
        MediaStoreCollections.collectionForSourceId(item.sourceId, sdkInt)
            ?: MediaStoreCollections.collectionForUri(item.uri, sdkInt)

    private fun sourceForCollectionUri(collectionUri: Uri): SourceRef {
        val collection = MediaStoreCollections.collectionForUri(collectionUri.toString(), sdkInt)
            ?: return SourceRef(
                id = SourceId(collectionUri.toString()),
                treeUri = collectionUri.toString(),
                displayName = collectionUri.lastPathSegment ?: "MediaStore",
                access = SourceRef.Access.RW,
                kind = SourceKind.MEDIASTORE,
                itemCount = null,
                totalBytes = null,
                grantedAt = 0L,
            )
        return MediaStoreCollections.sourceFor(collection, sdkInt)
    }

    private fun encodeToken(source: SourceRef, collection: MediaStoreCollection, offset: Int): PageToken =
        PageToken(json.encodeToString(Token.serializer(), Token(source.id.value, collection.name, offset)))

    private fun decodeToken(token: PageToken, source: SourceRef, collection: MediaStoreCollection): Int {
        val decoded = try {
            json.decodeFromString(Token.serializer(), token.value)
        } catch (error: Throwable) {
            throw IllegalArgumentException("malformed page token", error)
        }
        require(decoded.sourceId == source.id.value && decoded.collection == collection.name) {
            "page token belongs to a different source and must not be resumed here"
        }
        return decoded.offset
    }

    @Serializable
    private data class Token(val sourceId: String, val collection: String, val offset: Int)

    companion object {
        /** Rows per page; matches the SAF walker's paging budget. */
        const val DEFAULT_PAGE_SIZE: Int = 200

        /** Upper bound for a name-collision scan inside one destination directory. */
        private const val ROW_SCAN_LIMIT: Int = 10_000

        /** Explicit failure when no [QuarantineStore] was supplied. */
        const val QUARANTINE_NOT_CONFIGURED: String = "quarantine_not_configured"

        /** V4: the raw-path rename route is unusable on API 30+; the row route needs R. */
        const val MOVE_REQUIRES_API_30: String = "mediastore_move_requires_api_30"

        private val json = Json { ignoreUnknownKeys = true }
    }
}
