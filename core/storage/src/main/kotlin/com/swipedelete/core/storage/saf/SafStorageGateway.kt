package com.swipedelete.core.storage.saf

import android.content.Context
import android.os.StatFs
import com.swipedelete.core.common.Sha256Hasher
import com.swipedelete.core.model.BatchId
import com.swipedelete.core.model.Capacity
import com.swipedelete.core.model.ItemId
import com.swipedelete.core.model.JournalEntry
import com.swipedelete.core.model.MediaItem
import com.swipedelete.core.model.SourceRef
import com.swipedelete.core.storage.ChangeEvent
import com.swipedelete.core.storage.DeleteResult
import com.swipedelete.core.storage.Destination
import com.swipedelete.core.storage.Failure
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
import com.swipedelete.core.storage.quarantine.QuarantineStore
import com.swipedelete.core.storage.scan.ScanPage
import com.swipedelete.core.storage.scan.SourceScanPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * Tier 0 storage: the SAF storage gateway. It makes the app work with **no
 * restricted permissions** (spec §4.4), which is the policy-required default
 * state — everything the user can do lives inside the tree grants they gave
 * (spec §4.5).
 *
 * - [sources] returns the persisted grants ([PersistedGrantStore]).
 * - [enumerate] is a paged, cancellable, resumable walk of a granted tree
 *   (spec §9.4). Callers that need the honest progress — truncation, unreadable
 *   entries — use [enumerateDetailed], or the read-only [scan] port that carries
 *   the same progress through the assembled graph (D1); the frozen
 *   [StorageGateway] page cannot carry it.
 * - [move] is copy-then-delete inside (or between) granted trees, because SAF
 *   has no raw rename and `DocumentsContract.moveDocument` gives no O(1)
 *   guarantee (V3). It checks for a name collision *before* writing and picks
 *   ` (1)`, ` (2)`… itself, so it never overwrites. It never throws (other than
 *   a [kotlinx.coroutines.CancellationException]): a provider error comes back
 *   as [MoveResult.Failed] with the source untouched.
 * - [quarantine]/[restore] delegate to a caller-supplied [QuarantineStore]. The
 *   store is **not** created here: production wiring is
 *   `ProtectedRoots → Journaling → Quarantine → Concrete`, so this concrete
 *   gateway must not own a second quarantine root. Without a store, both calls
 *   return an explicit not-configured failure and touch nothing.
 *
 * Items are addressed by their *tree-relative path* (`"dir/a.gif"`), which is
 * the convention `MediaItem.uri`/`MediaItem.path` already use elsewhere in the
 * module (`"${source.treeUri}/${relativePath}"`). SAF guarantees display names
 * are unique within a parent, so a relative path is a stable identity inside a
 * tree.
 *
 * @param access the provider seam; [SafDocumentsContractAccess] in production,
 *   a fake in tests.
 * @param quarantineStore the AC-3 store, supplied by the caller (Task 24/25
 *   wiring). `null` means quarantine is not configured on this instance.
 */
class SafStorageGateway(
    context: Context,
    private val persisted: PersistedGrantStore,
    private val access: DocumentTreeAccess = SafDocumentsContractAccess(context),
    private val walker: DocumentTreeWalker = DocumentTreeWalker(access),
    private val quarantineStore: QuarantineStore? = null,
) : StorageGateway, ItemLocator, SourceScanPort, OriginalRestorer {

    /**
     * The volume [capacity] reports on. The quarantine root belongs to the
     * caller's [QuarantineStore]; this gateway never creates one (a second root
     * would be dead code under the production decorator stack).
     */
    private val capacityProbe: File = context.getExternalFilesDir(null) ?: context.filesDir

    override suspend fun sources(): List<SourceRef> = persisted.sources()

    override fun enumerate(source: SourceRef, page: PageToken?): Flow<Page<MediaItem>> =
        enumerateDetailed(source, page).map { Page(it.items, it.next) }

    /** [enumerate] with the honest per-page progress the frozen interface cannot carry. */
    fun enumerateDetailed(source: SourceRef, page: PageToken? = null): Flow<WalkPage> =
        walker.walk(source, SafUri.treeDocumentId(source.treeUri), page)

    /**
     * [SourceScanPort]: the read-only view of [enumerateDetailed], exposing the
     * walker's truncation flag and cumulative entry count. It yields the same
     * items as the frozen [enumerate] and touches nothing.
     */
    override fun scan(source: SourceRef, page: PageToken?): Flow<ScanPage> =
        enumerateDetailed(source, page).map { walked ->
            ScanPage(
                items = walked.items,
                next = walked.next,
                truncated = walked.progress.truncated,
                scanned = walked.progress.entriesScanned,
            )
        }

    override suspend fun metadata(item: MediaItem): Metadata {
        val source = sourceFor(item) ?: throw FileNotFoundException("unknown source ${item.sourceId.value}")
        val node = resolveNode(source, relativePathFor(source, item))
            ?: throw FileNotFoundException("not found under ${source.treeUri}: ${item.relativePath}")
        val meta = access.metadata(node)
        return Metadata(
            sizeBytes = meta.sizeBytes,
            mime = meta.mime,
            lastModified = meta.lastModified,
        )
    }

    override fun openStream(item: MediaItem): InputStream {
        // Resolution is suspend so the walker can be testable, but
        // ContentResolver queries are synchronous; this only blocks the calling
        // thread for the depth of the path (the store calls it on Dispatchers.IO).
        val node = runBlocking(Dispatchers.IO) { nodeFor(item) }
            ?: throw FileNotFoundException("not found: ${item.relativePath}")
        return access.openStream(node)
    }

    /**
     * Never throws — except a [CancellationException], which is rethrown
     * untouched — so a provider error anywhere in the destination listing or the
     * path resolvers comes back as [MoveResult.Failed] with the source
     * untouched, as the frozen contract promises.
     */
    override suspend fun move(item: MediaItem, destination: Destination): MoveResult = try {
        doMove(item, destination)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        MoveResult.Failed(error.message ?: "move_failed")
    }

    private suspend fun doMove(item: MediaItem, destination: Destination): MoveResult {
        val source = sourceFor(item) ?: return MoveResult.Failed("unknown_source")
        val relative = relativePathFor(source, item)
        if (relative.isBlank()) return MoveResult.Failed("item has no relative path")
        val sourceNode = resolveNode(source, relative) ?: return MoveResult.Failed("source_not_found")
        if (sourceNode.isDirectory) return MoveResult.Failed("refusing_to_move_a_directory")

        val target = destination.source
        if (target.access != SourceRef.Access.RW) return MoveResult.Failed("destination_not_writable")
        val targetDirectoryId = resolveDirectoryId(target, destination.relativePath)
            ?: return MoveResult.Failed("destination_not_found")
        val targetDirectory = destination.relativePath?.trim('/')?.takeIf { it.isNotEmpty() }.orEmpty()

        // Never overwrite (V3/AC-17): inspect the destination first and choose a
        // free ` (n)` name ourselves. SAF will happily replace otherwise.
        val existing = access.children(target.treeUri, targetDirectoryId).map { it.displayName }.toSet()
        val collision = sourceNode.displayName in existing
        val resolvedName = if (collision) freeName(existing, sourceNode.displayName) else sourceNode.displayName
        val resolvedRelative = listOf(targetDirectory, resolvedName)
            .filter { it.isNotEmpty() }
            .joinToString("/")

        val sourceMeta = access.metadata(sourceNode)

        // Copy first; the source is only touched once a verified copy exists.
        val created = try {
            access.createDocument(target.treeUri, targetDirectoryId, resolvedName, sourceNode.mime)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return MoveResult.Failed("create_failed: ${error.message}")
        }

        val copied = try {
            access.openStream(sourceNode).use { input ->
                access.openOutputStream(created).use { output -> input.copyTo(output) }
            }
        } catch (cancelled: CancellationException) {
            runCatching { access.delete(created) }
            throw cancelled
        } catch (error: Throwable) {
            runCatching { access.delete(created) }
            return MoveResult.Failed("copy_failed: ${error.message}")
        }

        val targetMeta = try {
            access.metadata(created)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            null
        }
        val verified = targetMeta != null &&
            targetMeta.sizeBytes == copied &&
            (sourceMeta.sizeBytes <= 0 || copied == sourceMeta.sizeBytes)
        if (!verified) {
            runCatching { access.delete(created) }
            return MoveResult.Failed("verification_failed")
        }

        // Delete the source last. A refused delete leaves the source untouched
        // and a verified copy present — a duplicate, never a loss.
        val deleted = try {
            access.delete(sourceNode)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            false
        }
        if (!deleted) return MoveResult.Failed("source_delete_failed")

        val moved = itemAt(target, resolvedRelative, sourceNode.mime, copied, sourceMeta.lastModified)
        return if (collision) MoveResult.NameCollision(moved) else MoveResult.Moved(moved)
    }

    override suspend fun quarantine(items: List<MediaItem>, batch: BatchId): QuarantineResult {
        val store = quarantineStore ?: return notConfigured(items)
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

    override suspend fun restore(entry: JournalEntry): RestoreResult {
        val store = quarantineStore ?: return RestoreResult.Conflict(entry.fromUri, QUARANTINE_NOT_CONFIGURED)
        return store.restore(entry)
    }

    /**
     * No store configured: every item carries an explicit failure and nothing is
     * touched. [QuarantineResult] has no dedicated failure variant, so a
     * [QuarantineResult.Partial] with one [Failure] per item is the honest form
     * (`/QuarantineStore.empty` uses the same pattern for a refusal).
     */
    private fun notConfigured(items: List<MediaItem>): QuarantineResult.Partial =
        QuarantineResult.Partial(
            entries = emptyList(),
            failures = items.map { Failure(it.id, QUARANTINE_NOT_CONFIGURED) },
        )

    override suspend fun deletePermanently(items: List<MediaItem>): DeleteResult {
        val failures = ArrayList<Failure>()
        var deleted = 0
        for (item in items) {
            val source = sourceFor(item)
            if (source == null) {
                failures += Failure(item.id, "unknown_source")
                continue
            }
            val node = resolveNode(source, relativePathFor(source, item))
            if (node == null) {
                failures += Failure(item.id, "not_found")
                continue
            }
            val ok = try {
                access.delete(node)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                false
            }
            if (ok) deleted++ else failures += Failure(item.id, "delete_refused")
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

    /** SAF trees offer no change notifications at Tier 0; a watch yields nothing. */
    override fun watch(source: SourceRef): Flow<ChangeEvent> = emptyFlow()

    /**
     * [ItemLocator]: resolve the synthetic tree URI a SAF item carries
     * (`"${treeUri}/${relativePath}"`) to a document under a grant the app still
     * holds. A URI naming a tree that is no longer persisted (a revoked grant)
     * resolves to `false`, which reconciliation reports as `unknown` rather than
     * guessing. Never throws: an unknown source, an unreadable parent or a
     * vanished document is simply absent.
     */
    override suspend fun exists(uri: String): Boolean {
        if (uri.isBlank()) return false
        val source = persisted.sources().firstOrNull {
            uri == it.treeUri || uri.startsWith("${it.treeUri.trimEnd('/')}/")
        } ?: return false
        val relative = SafUri.relativePath(source.treeUri, uri)
        if (relative.isBlank()) return false
        return resolveNode(source, relative) != null
    }

    // --- Internals ---

    private suspend fun nodeFor(item: MediaItem): DocumentNode? {
        val source = sourceFor(item) ?: return null
        return resolveNode(source, relativePathFor(source, item))
    }

    /**
     * AC-3 for a folder-grant item: recreates the document at its original
     * tree-relative path from the verified quarantine copy. Never overwrites
     * (AC-17), recreates a missing parent folder, and checks the written bytes
     * against the recorded hash before reporting success; any failure removes
     * what it created.
     */
    override suspend fun restoreOriginal(request: OriginalRestoreRequest): OriginalRestoreOutcome {
        val source = persisted.sources().firstOrNull { it.id == request.sourceId }
            ?: return OriginalRestoreOutcome.Unavailable(RESTORE_SOURCE_NOT_GRANTED)
        if (source.access != SourceRef.Access.RW) {
            return OriginalRestoreOutcome.Unavailable(RESTORE_SOURCE_NOT_WRITABLE)
        }
        val relative = (request.relativePath?.takeIf { it.isNotBlank() }
            ?: SafUri.relativePath(source.treeUri, request.originalUri)).trim('/')
        val name = relative.substringAfterLast('/')
        if (name.isEmpty()) return OriginalRestoreOutcome.Unavailable(RESTORE_NO_ORIGINAL_NAME)
        val restoredUri = SafUri.join(source.treeUri, relative)

        val directoryId = try {
            resolveOrCreateDirectoryId(source, relative.substringBeforeLast('/', missingDelimiterValue = ""))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return OriginalRestoreOutcome.Unavailable("$RESTORE_FOLDER_FAILED: ${error.message}")
        }
        if (access.children(source.treeUri, directoryId).any { it.displayName == name }) {
            return OriginalRestoreOutcome.Occupied(restoredUri)
        }

        val created = try {
            access.createDocument(source.treeUri, directoryId, name, request.mime)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return OriginalRestoreOutcome.Unavailable("$RESTORE_CREATE_FAILED: ${error.message}")
        }
        try {
            // A provider may silently pick another name instead of failing on a
            // collision; that is not the original place, so it is undone.
            if (access.metadata(created).displayName != name) {
                access.delete(created)
                return OriginalRestoreOutcome.Occupied(restoredUri)
            }
            request.open().use { input ->
                access.openOutputStream(created).use { output -> input.copyTo(output) }
            }
            val written = Sha256Hasher().sha256(access.openStream(created))
            if (written != request.sha256) {
                access.delete(created)
                return OriginalRestoreOutcome.Unavailable(RESTORE_VERIFY_FAILED)
            }
        } catch (cancelled: CancellationException) {
            runCatching { access.delete(created) }
            throw cancelled
        } catch (error: Throwable) {
            runCatching { access.delete(created) }
            return OriginalRestoreOutcome.Unavailable("$RESTORE_WRITE_FAILED: ${error.message}")
        }
        return OriginalRestoreOutcome.Restored(restoredUri)
    }

    /** Like [resolveDirectoryId], but creates each missing folder on the way down. */
    private suspend fun resolveOrCreateDirectoryId(source: SourceRef, relativePath: String): String {
        var directoryId = SafUri.treeDocumentId(source.treeUri)
        for (segment in relativePath.trim('/').split('/').filter { it.isNotEmpty() }) {
            val match = access.children(source.treeUri, directoryId).firstOrNull { it.displayName == segment }
            directoryId = when {
                match == null ->
                    access.createDocument(source.treeUri, directoryId, segment, MIME_DIRECTORY).documentId
                match.isDirectory -> match.documentId
                else -> throw IllegalStateException("$segment is a file, not a folder")
            }
        }
        return directoryId
    }

    private suspend fun sourceFor(item: MediaItem): SourceRef? =
        persisted.sources().firstOrNull { it.id == item.sourceId }

    private fun relativePathFor(source: SourceRef, item: MediaItem): String =
        item.relativePath?.takeIf { it.isNotBlank() }
            ?: SafUri.relativePath(source.treeUri, item.uri)

    /** Resolves a tree-relative path to the document it names, one segment at a time. */
    private suspend fun resolveNode(source: SourceRef, relativePath: String): DocumentNode? {
        val segments = relativePath.trim('/').split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null
        var parentId = SafUri.treeDocumentId(source.treeUri)
        var current: DocumentNode? = null
        segments.forEachIndexed { index, segment ->
            val match = access.children(source.treeUri, parentId).firstOrNull { it.displayName == segment }
                ?: return null
            if (index < segments.lastIndex && !match.isDirectory) return null
            current = match
            parentId = match.documentId
        }
        return current
    }

    /** The document id of the directory a relative path names; the root for a blank path. */
    private suspend fun resolveDirectoryId(source: SourceRef, relativePath: String?): String? {
        var directoryId = SafUri.treeDocumentId(source.treeUri)
        val segments = relativePath?.trim('/')?.split('/')?.filter { it.isNotEmpty() }.orEmpty()
        for (segment in segments) {
            val match = access.children(source.treeUri, directoryId)
                .firstOrNull { it.displayName == segment && it.isDirectory }
                ?: return null
            directoryId = match.documentId
        }
        return directoryId
    }

    private fun itemAt(
        source: SourceRef,
        relativePath: String,
        mime: String,
        sizeBytes: Long,
        lastModified: Long,
    ): MediaItem {
        val uri = SafUri.join(source.treeUri, relativePath)
        return MediaItem(
            // `sourceId:relativePath` — the same id namespace the raw gateway mints
            // (`RawStorageGateway.itemFor`) and the frozen `StorageGatewayContractTest`
            // fixture uses. M0-T17's device pass caught this gateway emitting
            // `sourceId/relativePath`, which made the contract's post-cancel lookup
            // fail even though the file was intact.
            id = ItemId("${source.id.value}:$relativePath"),
            uri = uri,
            path = uri,
            relativePath = relativePath,
            mediaStoreId = null,
            mime = mime,
            sizeBytes = sizeBytes,
            width = null,
            height = null,
            durationMs = null,
            dateAdded = 0L,
            dateModified = lastModified,
            bucket = relativePath.substringBeforeLast('/', "").ifEmpty { null },
            sourceId = source.id,
            hash = null,
        )
    }

    companion object {
        /** Conventional directory name for the quarantine root, for caller wiring. */
        const val QUARANTINE_DIR: String = "quarantine"

        /** Explicit failure reason when no [QuarantineStore] was supplied. */
        const val QUARANTINE_NOT_CONFIGURED: String = "quarantine_not_configured"

        // Stable reason codes of a refused [restoreOriginal].
        const val RESTORE_SOURCE_NOT_GRANTED: String = "source_not_granted"
        const val RESTORE_SOURCE_NOT_WRITABLE: String = "source_not_writable"
        const val RESTORE_NO_ORIGINAL_NAME: String = "no_original_name"
        const val RESTORE_FOLDER_FAILED: String = "restore_folder_failed"
        const val RESTORE_CREATE_FAILED: String = "restore_create_failed"
        const val RESTORE_WRITE_FAILED: String = "restore_write_failed"
        const val RESTORE_VERIFY_FAILED: String = "restore_verify_failed"

        private const val MIME_DIRECTORY = "vnd.android.document/directory"

        /**
         * First free `name`, `name (1)`, `name (2)`… among [existing]. A dotfile
         * (`.hidden`) has no extension, so the suffix is appended to the whole
         * name so the result stays a dotfile.
         */
        fun freeName(existing: Set<String>, name: String): String {
            if (name !in existing) return name
            val dot = name.lastIndexOf('.')
            val hasExtension = dot > 0
            val base = if (hasExtension) name.substring(0, dot) else name
            val extension = if (hasExtension) name.substring(dot) else ""
            var counter = 1
            var candidate = "$base ($counter)$extension"
            while (candidate in existing) {
                counter++
                candidate = "$base ($counter)$extension"
            }
            return candidate
        }
    }
}
