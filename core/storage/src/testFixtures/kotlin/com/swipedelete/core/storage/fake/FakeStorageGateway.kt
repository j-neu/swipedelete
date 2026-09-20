package com.swipedelete.core.storage.fake

import com.swipedelete.core.model.BatchId
import com.swipedelete.core.model.Capacity
import com.swipedelete.core.model.ItemId
import com.swipedelete.core.model.JournalEntry
import com.swipedelete.core.model.JournalState
import com.swipedelete.core.model.MediaItem
import com.swipedelete.core.model.OpKind
import com.swipedelete.core.model.SourceId
import com.swipedelete.core.model.SourceKind
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * In-memory [StorageGateway] backed by a path-keyed map of [MediaItem]s.
 *
 * It is the reference implementation of the gateway contract and the backbone
 * of the chaos tests (M0-T10): [beforeMove] and [faultInjector] let a test
 * suspend or fail an operation at a controlled point, and [quarantineContents]
 * / [fileExists] let a test observe exactly what was and was not moved.
 */
class FakeStorageGateway(
    initialItems: List<MediaItem> = emptyList(),
) : StorageGateway {

    val faultInjector: FaultInjector = FaultInjector()

    /** Called with the 0-based index of the item about to be moved. */
    var beforeMove: (suspend (Int) -> Unit)?
        get() = faultInjector.beforeMove
        set(value) {
            faultInjector.beforeMove = value
        }

    private val mutex = Mutex()
    private val files = LinkedHashMap<String, MediaItem>()
    private val contents = LinkedHashMap<String, ByteArray>()
    private val sourcesById = LinkedHashMap<SourceId, SourceRef>()
    private val changes = MutableSharedFlow<ChangeEvent>(extraBufferCapacity = 64)

    init {
        initialItems.forEach { register(it) }
    }

    // --- Fixture / observation API (not part of StorageGateway) ---

    /** Registers a source so [createFileAt] can attribute files to it. */
    fun addSource(source: SourceRef) {
        sourcesById[source.id] = source
    }

    /**
     * When set, [capacity] reports this many free bytes instead of deriving them
     * from the registered files. Lets a test force the space pre-flight without
     * fabricating a nearly-full volume.
     */
    var capacityFreeBytes: Long? = null

    /**
     * Registers [item] so the gateway can serve it, and the bytes [openStream]
     * will yield for it. The default is empty content, which keeps every
     * pre-existing test that only checks existence working unchanged.
     */
    fun register(item: MediaItem, content: ByteArray = ByteArray(0)) {
        val path = item.path ?: return
        files[path] = item
        contents[path] = content
        sourcesById.getOrPut(item.sourceId) {
            sourceFor(item.sourceId, path.substringBeforeLast('/', ""))
        }
    }

    /** Creates a file that physically occupies [path], as if another app wrote it. */
    fun createFileAt(path: String, mime: String = mimeFor(path), sizeBytes: Long = 1024L) {
        val sourceId = sourceIdForPath(path)
        files[path] = MediaItem(
            id = ItemId("file:$path"),
            uri = path,
            path = path,
            relativePath = path.substringAfterLast('/'),
            mediaStoreId = null,
            mime = mime,
            sizeBytes = sizeBytes,
            width = null,
            height = null,
            durationMs = null,
            dateAdded = 0L,
            dateModified = 0L,
            bucket = null,
            sourceId = sourceId,
            hash = null,
        )
    }

    fun fileExists(path: String): Boolean = files.containsKey(path)

    /** Every item currently sitting under the quarantine root. */
    fun quarantineContents(): List<MediaItem> =
        files.values.filter { it.path?.startsWith("$QUARANTINE_ROOT/") == true }

    // --- StorageGateway ---

    override suspend fun sources(): List<SourceRef> = sourcesById.values.toList()

    override fun enumerate(source: SourceRef, page: PageToken?): Flow<Page<MediaItem>> = flow {
        faultInjector.beforeEnumerate?.invoke(source)
        if (faultInjector.failNextEnumerate) {
            faultInjector.failNextEnumerate = false
            throw IllegalStateException("injected enumerate failure")
        }
        val ordered = files.values
            .filter { it.sourceId == source.id }
            .sortedBy { it.path ?: "" }
        emit(Page(ordered, null))
    }

    override suspend fun metadata(item: MediaItem): Metadata {
        val stored = item.path?.let { files[it] } ?: item
        return Metadata(
            sizeBytes = stored.sizeBytes,
            mime = stored.mime,
            lastModified = stored.dateModified,
            sha256 = stored.hash,
            width = stored.width,
            height = stored.height,
            durationMs = stored.durationMs,
        )
    }

    override fun openStream(item: MediaItem): InputStream {
        val content = item.path?.let { contents[it] }
        if (content != null) return ByteArrayInputStream(content)
        val size = (item.path?.let { files[it]?.sizeBytes } ?: item.sizeBytes)
            .coerceIn(0L, 1_048_576L)
            .toInt()
        return ByteArrayInputStream(ByteArray(size))
    }

    override suspend fun move(item: MediaItem, destination: Destination): MoveResult {
        val from = item.path ?: return MoveResult.Failed("item ${item.id.value} has no path")
        if (!files.containsKey(from)) return MoveResult.Failed("source not found: $from")
        beforeMove?.invoke(0)
        if (faultInjector.failNextMove) {
            faultInjector.failNextMove = false
            return MoveResult.Failed("injected move failure")
        }
        return mutex.withLock { moveLocked(item, from, destination) }
    }

    override suspend fun quarantine(items: List<MediaItem>, batch: BatchId): QuarantineResult {
        val needed = items.sumOf { it.sizeBytes }
        val capacity = capacity()
        if (needed > capacity.freeBytes) {
            return QuarantineResult.InsufficientSpace(needed, capacity.freeBytes)
        }

        val entries = ArrayList<JournalEntry>(items.size)
        val failures = ArrayList<Failure>()
        items.forEachIndexed { index, item ->
            beforeMove?.invoke(index)
            val entry = mutex.withLock { quarantineLocked(item, batch) }
            if (entry == null) {
                failures += Failure(item.id, "source not found: ${item.path}")
            } else {
                entries += entry
            }
        }
        return if (failures.isEmpty()) {
            QuarantineResult.Quarantined(batch, entries)
        } else {
            QuarantineResult.Partial(entries, failures)
        }
    }

    override suspend fun restore(entry: JournalEntry): RestoreResult = mutex.withLock {
        val original = entry.fromUri
        val quarantinedPath = entry.toUri
        if (files.containsKey(original)) {
            return@withLock RestoreResult.Conflict(original, files.getValue(original).uri)
        }
        val quarantined = quarantinedPath?.let { files[it] }
            ?: return@withLock RestoreResult.Conflict(original, "")
        val expected = entry.hash
        val actual = quarantined.hash
        if (expected != null && actual != null && expected != actual) {
            return@withLock RestoreResult.HashMismatch(expected, actual)
        }
        files.remove(quarantinedPath)
        val restored = quarantined.copy(uri = original, path = original)
        files[original] = restored
        changes.tryEmit(ChangeEvent.Added(restored))
        RestoreResult.Restored(original)
    }

    override suspend fun deletePermanently(items: List<MediaItem>): DeleteResult = mutex.withLock {
        val failures = ArrayList<Failure>()
        var deleted = 0
        items.forEach { item ->
            val path = item.path
            if (path != null && files.remove(path) != null) {
                deleted++
                changes.tryEmit(ChangeEvent.Removed(item.id))
            } else {
                failures += Failure(item.id, "source not found: $path")
            }
        }
        if (failures.isEmpty()) DeleteResult.Deleted(deleted) else DeleteResult.Partial(deleted, failures)
    }

    override suspend fun capacity(): Capacity {
        val used = files.values.sumOf { it.sizeBytes }
        return Capacity(
            totalBytes = TOTAL_BYTES,
            usedBytes = used,
            freeBytes = capacityFreeBytes ?: (TOTAL_BYTES - used),
            reclaimableBytes = 0L,
        )
    }

    override fun watch(source: SourceRef): Flow<ChangeEvent> = changes.asSharedFlow()

    // --- Internals ---

    private fun sourceFor(id: SourceId, treeUri: String) = SourceRef(
        id = id,
        treeUri = treeUri,
        displayName = id.value,
        access = SourceRef.Access.RW,
        kind = SourceKind.RAW,
        itemCount = null,
        totalBytes = null,
        grantedAt = 0L,
    )

    private fun sourceIdForPath(path: String): SourceId {
        val match = sourcesById.values
            .filter { path == it.treeUri || path.startsWith("${it.treeUri}/") }
            .maxByOrNull { it.treeUri.length }
        if (match != null) return match.id
        val derivedUri = path.substringBeforeLast('/', "")
        return sourcesById.getOrPut(SourceId(derivedUri)) { sourceFor(SourceId(derivedUri), derivedUri) }.id
    }

    /** Moves [item] into [destination], disambiguating a taken filename. Must hold [mutex]. */
    private fun moveLocked(item: MediaItem, from: String, destination: Destination): MoveResult {
        if (!files.containsKey(from)) return MoveResult.Failed("source not found: $from")
        val directory = destination.relativePath
            ?.trim('/')
            ?.takeIf { it.isNotEmpty() }
            ?.let { "${destination.source.treeUri}/$it" }
            ?: destination.source.treeUri
        val name = from.substringAfterLast('/')
        val direct = "$directory/$name"
        // Moving an item into the directory it already occupies is a no-op, not a collision.
        if (direct == from) return MoveResult.Moved(item)
        val collision = files.containsKey(direct)
        val target = if (collision) freePath(directory, name) else direct
        val moved = item.copy(uri = target, path = target, sourceId = destination.source.id)
        files.remove(from)
        files[target] = moved
        changes.tryEmit(ChangeEvent.Modified(moved))
        return if (collision) MoveResult.NameCollision(moved) else MoveResult.Moved(moved)
    }

    /**
     * Moves [item] into the quarantine store atomically (no suspension inside).
     * Returns the DONE journal entry, or null when the source is missing.
     * Must hold [mutex].
     */
    private fun quarantineLocked(item: MediaItem, batch: BatchId): JournalEntry? {
        val from = item.path ?: return null
        val stored = files[from] ?: return null
        val directory = "$QUARANTINE_ROOT/${batch.value}"
        val target = freePath(directory, from.substringAfterLast('/'))
        files.remove(from)
        files[target] = stored.copy(uri = target, path = target)
        changes.tryEmit(ChangeEvent.Removed(stored.id))
        return JournalEntry(
            id = "q:${batch.value}:${item.id.value}",
            batchId = batch,
            sessionId = null,
            itemId = item.id,
            opKind = OpKind.QUARANTINE,
            fromUri = from,
            toUri = target,
            hash = stored.hash,
            sizeBytes = stored.sizeBytes,
            state = JournalState.DONE,
            createdAt = 0L,
            expiresAt = 0L,
        )
    }

    /** First free `name`, `name (1)`, `name (2)`, ... in [directory]. */
    private fun freePath(directory: String, name: String): String {
        // A dotfile (".hidden") has no extension: suffix the whole name so the
        // result stays a dotfile (".hidden (1)") instead of " (1).hidden".
        val dot = name.lastIndexOf('.')
        val hasExtension = dot > 0
        val base = if (hasExtension) name.substring(0, dot) else name
        val extension = if (hasExtension) name.substring(dot) else ""
        var candidate = "$directory/$name"
        var counter = 1
        while (files.containsKey(candidate)) {
            candidate = "$directory/$base ($counter)$extension"
            counter++
        }
        return candidate
    }

    companion object {
        const val QUARANTINE_ROOT: String = "fake://quarantine"
        const val TOTAL_BYTES: Long = 1_000_000_000L

        fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "gif" -> "image/gif"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "mp4" -> "video/mp4"
            else -> "application/octet-stream"
        }
    }
}
