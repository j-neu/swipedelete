package com.swipedelete.core.storage.saf

import android.content.Context
import android.os.StatFs
import com.swipedelete.core.common.Clock
import com.swipedelete.core.common.Hasher
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
import com.swipedelete.core.storage.Metadata
import com.swipedelete.core.storage.MoveResult
import com.swipedelete.core.storage.Page
import com.swipedelete.core.storage.PageToken
import com.swipedelete.core.storage.QuarantineResult
import com.swipedelete.core.storage.RestoreResult
import com.swipedelete.core.storage.StorageGateway
import com.swipedelete.core.storage.quarantine.QuarantineStore
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
 *   entries — use [enumerateDetailed]; the frozen [StorageGateway] page cannot
 *   carry it.
 * - [move] is copy-then-delete inside (or between) granted trees, because SAF
 *   has no raw rename and `DocumentsContract.moveDocument` gives no O(1)
 *   guarantee (V3). It checks for a name collision *before* writing and picks
 *   ` (1)`, ` (2)`… itself, so it never overwrites.
 * - [quarantine]/[restore] run through [QuarantineStore], the AC-3
 *   copy → verify → delete store.
 *
 * Items are addressed by their *tree-relative path* (`"dir/a.gif"`), which is
 * the convention `MediaItem.uri`/`MediaItem.path` already use elsewhere in the
 * module (`"${source.treeUri}/${relativePath}"`). SAF guarantees display names
 * are unique within a parent, so a relative path is a stable identity inside a
 * tree.
 *
 * @param access the provider seam; [SafDocumentsContractAccess] in production,
 *   a fake in tests.
 */
class SafStorageGateway(
    context: Context,
    private val persisted: PersistedGrantStore,
    private val access: DocumentTreeAccess = SafDocumentsContractAccess(context),
    private val hasher: Hasher = Sha256Hasher(),
    private val clock: Clock = SystemClock,
    private val quarantineRoot: File = File(
        context.getExternalFilesDir(null) ?: context.filesDir,
        QUARANTINE_DIR,
    ),
    private val walker: DocumentTreeWalker = DocumentTreeWalker(access),
) : StorageGateway {

    private val quarantineStore: QuarantineStore = QuarantineStore(quarantineRoot, this, hasher, clock)

    init {
        // `capacity()` stats the root before anything is stored; make sure the
        // path exists so the pre-flight is never an I/O error.
        quarantineRoot.mkdirs()
    }

    override suspend fun sources(): List<SourceRef> = persisted.sources()

    override fun enumerate(source: SourceRef, page: PageToken?): Flow<Page<MediaItem>> =
        enumerateDetailed(source, page).map { Page(it.items, it.next) }

    /** [enumerate] with the honest per-page progress the frozen interface cannot carry. */
    fun enumerateDetailed(source: SourceRef, page: PageToken? = null): Flow<WalkPage> =
        walker.walk(source, SafUri.treeDocumentId(source.treeUri), page)

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

    override suspend fun move(item: MediaItem, destination: Destination): MoveResult {
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

        val sourceMeta = try {
            access.metadata(sourceNode)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return MoveResult.Failed("source_metadata_failed")
        }

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
        quarantineStore.preflight(items)?.let { return it }

        val entries = ArrayList<JournalEntry>(items.size)
        val failures = ArrayList<Failure>()
        items.forEach { item ->
            try {
                entries += quarantineStore.store(item, batch)
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

    override suspend fun restore(entry: JournalEntry): RestoreResult = quarantineStore.restore(entry)

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
        val stats = StatFs(quarantineStoreRoot())
        val total = stats.totalBytes
        val free = stats.availableBytes
        Capacity(totalBytes = total, usedBytes = total - free, freeBytes = free, reclaimableBytes = 0L)
    } catch (error: Throwable) {
        Capacity(totalBytes = 0L, usedBytes = 0L, freeBytes = 0L, reclaimableBytes = 0L)
    }

    /** SAF trees offer no change notifications at Tier 0; a watch yields nothing. */
    override fun watch(source: SourceRef): Flow<ChangeEvent> = emptyFlow()

    // --- Internals ---

    private suspend fun nodeFor(item: MediaItem): DocumentNode? {
        val source = sourceFor(item) ?: return null
        return resolveNode(source, relativePathFor(source, item))
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
            id = ItemId("${source.id.value}/$relativePath"),
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

    private fun quarantineStoreRoot(): String = quarantineRoot.absolutePath

    companion object {
        const val QUARANTINE_DIR: String = "quarantine"

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

/** The wall clock used when the caller does not supply one. */
internal object SystemClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}
