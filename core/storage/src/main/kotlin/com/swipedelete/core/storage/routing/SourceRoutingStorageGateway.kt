package com.swipedelete.core.storage.routing

import com.swipedelete.core.model.BatchId
import com.swipedelete.core.model.Capacity
import com.swipedelete.core.model.JournalEntry
import com.swipedelete.core.model.MediaItem
import com.swipedelete.core.model.SourceKind
import com.swipedelete.core.model.SourceRef
import com.swipedelete.core.storage.ChangeEvent
import com.swipedelete.core.storage.DeleteResult
import com.swipedelete.core.storage.Destination
import com.swipedelete.core.storage.ItemLocator
import com.swipedelete.core.storage.Metadata
import com.swipedelete.core.storage.MoveResult
import com.swipedelete.core.storage.OriginalRestoreOutcome
import com.swipedelete.core.storage.OriginalRestoreRequest
import com.swipedelete.core.storage.OriginalRestorer
import com.swipedelete.core.storage.Page
import com.swipedelete.core.storage.PageToken
import com.swipedelete.core.storage.QuarantineResult
import com.swipedelete.core.storage.RestoreResult
import com.swipedelete.core.storage.StorageGateway
import com.swipedelete.core.storage.scan.ScanPage
import com.swipedelete.core.storage.scan.SourceScanPort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.io.InputStream

/**
 * The concrete gateway for a build that has more than one storage backend.
 *
 * M1 binds two: the SAF gateway (granted folders) and the MediaStore gateway
 * (Tier-1 photo/video). Everything above this class — the Quarantine, Journaling
 * and ProtectedRoots decorators, the reconciler, the deck — sees one
 * [StorageGateway]; this class picks the right child per call, by source for the
 * walk ([enumerate]/[watch]`/`[scan]) and by item identity for the item operations
 * ([metadata]/[openStream]/[move] and friends). A MediaStore item carries a
 * non-null [MediaItem.mediaStoreId]; a SAF item does not.
 *
 * It also implements [SourceScanPort] by routing, so `DeckBuilder` can scan either
 * kind of source through the one read-only seam. Like every concrete gateway it
 * owns **no** quarantine store: the Quarantine decorator owns the one store, and
 * this class is constructed with no store and never bound as a Hilt dependency
 * (see `StorageModule`).
 *
 * The `quarantine` entry point is unreachable through the production stack (the
 * Quarantine decorator intercepts it before the concrete gateway), so it simply
 * routes the batch to the child the first item belongs to; the decorator's
 * `QuarantineStore` reaches back here only for `metadata`/`openStream`/
 * `deletePermanently`, which are all routed per item.
 */
class SourceRoutingStorageGateway(
    private val saf: StorageGateway,
    private val mediaStore: StorageGateway,
) : StorageGateway, SourceScanPort, ItemLocator, OriginalRestorer {

    private val safLocator: ItemLocator? get() = saf as? ItemLocator
    private val mediaLocator: ItemLocator? get() = mediaStore as? ItemLocator

    private fun forSource(source: SourceRef): StorageGateway =
        if (source.kind == SourceKind.MEDIASTORE) mediaStore else saf

    private fun forItem(item: MediaItem?): StorageGateway =
        if (item?.mediaStoreId != null) mediaStore else saf

    // --- StorageGateway ---

    override suspend fun sources(): List<SourceRef> = saf.sources() + mediaStore.sources()

    override fun enumerate(source: SourceRef, page: PageToken?): Flow<Page<MediaItem>> =
        forSource(source).enumerate(source, page)

    override suspend fun metadata(item: MediaItem): Metadata = forItem(item).metadata(item)

    override fun openStream(item: MediaItem): InputStream = forItem(item).openStream(item)

    override suspend fun move(item: MediaItem, destination: Destination): MoveResult =
        forItem(item).move(item, destination)

    override suspend fun quarantine(items: List<MediaItem>, batch: BatchId): QuarantineResult =
        forItem(items.firstOrNull()).quarantine(items, batch)

    override suspend fun restore(entry: JournalEntry): RestoreResult =
        if (entry.itemId.value.startsWith(MEDIASTORE_ITEM_PREFIX)) mediaStore.restore(entry) else saf.restore(entry)

    override suspend fun deletePermanently(items: List<MediaItem>): DeleteResult =
        forItem(items.firstOrNull()).deletePermanently(items)

    override suspend fun capacity(): Capacity = saf.capacity()

    override fun watch(source: SourceRef): Flow<ChangeEvent> = forSource(source).watch(source)

    // --- SourceScanPort ---

    override fun scan(source: SourceRef, page: PageToken?): Flow<ScanPage> {
        val child = forSource(source)
        val port = child as? SourceScanPort
        if (port != null) return port.scan(source, page)
        // A child that cannot scan is still walkable through the frozen
        // enumerate; without a depth/entry cap there is no truncation to report.
        var scanned = 0
        return child.enumerate(source, page).map { pageOfItems ->
            scanned += pageOfItems.items.size
            ScanPage(pageOfItems.items, pageOfItems.next, truncated = false, scanned = scanned)
        }
    }

    // --- ItemLocator ---

    override suspend fun exists(uri: String): Boolean {
        val bySaf = safLocator?.let { runCatching { it.exists(uri) }.getOrDefault(false) } ?: false
        if (bySaf) return true
        return mediaLocator?.let { runCatching { it.exists(uri) }.getOrDefault(false) } ?: false
    }

    // --- OriginalRestorer ---

    /**
     * Routed like every item operation: a catalogue row to the MediaStore child,
     * anything else to the SAF child. A child that cannot restore reports the
     * same refusal the quarantine store gives when no gateway can.
     */
    override suspend fun restoreOriginal(request: OriginalRestoreRequest): OriginalRestoreOutcome {
        val child = if (request.mediaStoreId != null) mediaStore else saf
        return (child as? OriginalRestorer)?.restoreOriginal(request)
            ?: OriginalRestoreOutcome.Unavailable(NO_RESTORER)
    }

    private companion object {
        /** `MediaItem.id` for a catalogue row, see `MediaStoreCursorMapper`. */
        const val MEDIASTORE_ITEM_PREFIX = "mediastore:"

        /** Same code as `QuarantineStore.NO_ORIGINAL_PATH`. */
        const val NO_RESTORER = "no_raw_original_path"
    }
}
