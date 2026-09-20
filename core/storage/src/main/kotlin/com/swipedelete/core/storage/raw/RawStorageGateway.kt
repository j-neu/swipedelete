package com.swipedelete.core.storage.raw

import android.os.Build
import android.os.Environment
import android.os.StatFs
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
import com.swipedelete.core.storage.saf.SafStorageGateway
import com.swipedelete.core.storage.scan.ScanPage
import com.swipedelete.core.storage.scan.SourceScanPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.InputStream

/** One directory the raw scan could not read, with why. Never silently dropped. */
data class RawScanError(val path: String, val reason: String)

/**
 * The honest result of a raw scan — the non-interface counterpart to the frozen
 * [Page] (whose shape cannot carry progress, spec §9.4).
 *
 * This is the Tier-2 analogue of `SafStorageGateway.enumerateDetailed`'s
 * `WalkPage`: the same conventions — an explicit [truncated] flag, a cumulative
 * [unreadableEntries] count and a named [errors] list — but it reports *media
 * exclusion* as well, which is what makes AC-10 observable. [mediaSkipped] is
 * split by how the file was classified ([mediaSkippedByExtension],
 * [mediaSkippedByContent], [unreadableMediaSkipped]), and out-of-root
 * directory children are counted in [outOfRootEntries]. It deliberately does
 * not introduce a second truncation mechanism on the frozen interface:
 * [RawStorageGateway.enumerate] still returns a plain `Page`, and callers that
 * need the honest report use [RawStorageGateway.scan].
 */
data class RawScanReport(
    val source: SourceRef,
    val items: List<MediaItem>,
    val entriesScanned: Int,
    val mediaSkipped: Int,
    val mediaSkippedByExtension: Int,
    val mediaSkippedByContent: Int,
    val unreadableMediaSkipped: Int,
    val unreadableEntries: Int,
    val outOfRootEntries: Int,
    val directoriesVisited: Int,
    val maxDepth: Int,
    val truncated: Boolean,
    val errors: List<RawScanError>,
)

/**
 * Path canonicalisation and containment, shared by every raw traversal.
 *
 * [File.isDirectory] and [File.listFiles] follow symlinks, so a link inside a
 * root can resolve outside it. Every traversal therefore canonicalises a
 * directory child before descending and skips it unless [isWithin] says it is
 * genuinely inside the root.
 */
internal object RawPaths {

    /**
     * The real path of [file], with symbolic links resolved. `File.canonicalFile`
     * does not resolve directory symlinks on Windows, so [java.nio.file.Path.toRealPath]
     * is used when the path exists; non-existent paths fall back to canonical /
     * absolute form.
     */
    fun canonical(file: File): File = try {
        file.toPath().toRealPath().toFile()
    } catch (error: Throwable) {
        runCatching { file.canonicalFile }.getOrElse { file.absoluteFile }
    }

    /** True when [file] is [root] itself or a descendant of it. */
    fun isWithin(file: File, root: File): Boolean =
        file == root || file.path.startsWith(root.path + File.separator)
}

/**
 * Tier 2 storage: raw-path access to shared storage, available **only** when the
 * user has granted All Files Access (`MANAGE_EXTERNAL_STORAGE`, spec §4.4).
 *
 * This is not a general-purpose gateway. It exists for the non-media file
 * management that MediaStore cannot do and SAF cannot do globally: APKs
 * anywhere, empty folders, partial downloads, zero-byte files, orphaned app
 * dirs, residual files, the OS thumbnail cache, and cross-tree moves (§4.4 Tier
 * 2). **Media is never served here** — spec §4.2 condition 2 makes that the
 * single strongest element of the All Files Access declaration, and §4.6 keeps
 * media on MediaStore.
 *
 * ### AC-10 is enforced structurally
 * [RawMediaPolicy] is consulted at every entry point, so media cannot "escape"
 * through any method. A file is media when its MIME type is media, when its name
 * carries a media extension, **or** when its leading bytes match a known media
 * signature — so a JPEG named `photo` or an MP4 renamed `clip.tmp` is still
 * refused:
 * - [metadata] / [openStream] on a media item throw [MediaNotPermittedException].
 * - [move] on a media item returns `MoveResult.Failed("media_not_permitted")`.
 * - [quarantine] / [deletePermanently] list a media item as an explicit
 *   [Failure] (`media_not_permitted`) and still process the non-media items in
 *   the same call.
 * - [enumerate] / [scan] **skip** media files entirely and report how many they
 *   skipped ([RawScanReport.mediaSkipped], split by classification basis).
 *
 * ### Bounds and honesty (spec §9.4)
 * A scan is breadth-first with [maxDepth] (default [DEFAULT_MAX_DEPTH]) and
 * [maxEntries] (default [DEFAULT_MAX_ENTRIES]) caps. Either cap sets
 * [RawScanReport.truncated] explicitly; a directory that cannot be listed is
 * counted in [RawScanReport.unreadableEntries] and named in
 * [RawScanReport.errors] — never dropped as if it did not exist.
 *
 * ### Roots are explicit
 * The gateway only ever walks the [roots] it was constructed with. A path an
 * item names is canonicalised and refused unless it sits inside a root; a root
 * that does not exist, is not a directory, or is a filesystem root is rejected
 * at construction.
 *
 * ### Host-testability
 * The Android and filesystem dependencies are injectable:
 * - [capacityOf] defaults to [statFsCapacity] (`StatFs`), so `capacity()` never
 *   needs an Android runtime in a test.
 * - [listChildren] defaults to `File.listFiles()`, the single directory-listing
 *   primitive the scan uses; the default is the production behaviour, and
 *   replacing it is how an unreadable directory or an out-of-root child is
 *   simulated on a host (the same seam pattern Task 20 used for
 *   `DocumentTreeAccess`).
 * - [readBytes] defaults to [RawMediaPolicy.readLeadingBytes], so the
 *   unreadable-body rule can be exercised deterministically on a host.
 * The All Files Access flag itself is a plain constructor argument, and the
 * environment probe is a function parameter of [fromEnvironment], so no test
 * depends on the device's real permission state.
 *
 * @param roots the directories this gateway may touch. Each must exist, be a
 *   directory, and not be a filesystem root.
 * @param hasAllFilesAccess the Tier-2 capability. `false` makes construction
 *   fail: the DI graph (Task 24) must not build this gateway at Tier 0/1.
 * @param capacityOf the volume probe behind [capacity].
 * @param listChildren the directory-listing primitive; `null` means unreadable.
 * @param maxDepth the descent cap, in levels below a root.
 * @param maxEntries the per-scan entry cap.
 * @param readBytes the leading-bytes reader behind media sniffing; `null` means
 *   unreadable, which is classified as media.
 */
class RawStorageGateway(
    private val roots: List<File>,
    hasAllFilesAccess: Boolean,
    private val capacityOf: (File) -> Capacity = ::statFsCapacity,
    private val listChildren: (File) -> List<File>? = { it.listFiles()?.toList() },
    private val maxDepth: Int = DEFAULT_MAX_DEPTH,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val readBytes: (File) -> ByteArray? = RawMediaPolicy::readLeadingBytes,
) : StorageGateway, ItemLocator, SourceScanPort {

    private val canonicalRoots: List<File>
    private val sourceRefs: List<SourceRef>

    init {
        check(hasAllFilesAccess) {
            "RawStorageGateway requires All Files Access (MANAGE_EXTERNAL_STORAGE); " +
                "it must not be constructed at Tier 0/1 (spec §4.4)."
        }
        require(roots.isNotEmpty()) { "RawStorageGateway needs at least one root" }
        require(maxDepth >= 0) { "maxDepth must not be negative, was $maxDepth" }
        require(maxEntries > 0) { "maxEntries must be positive, was $maxEntries" }
        canonicalRoots = roots.map { validatedRoot(it) }
        sourceRefs = canonicalRoots.map { sourceFor(it) }
    }

    // --- StorageGateway ---

    override suspend fun sources(): List<SourceRef> = sourceRefs

    /**
     * The frozen page view of a raw scan: media is skipped (AC-10) and a single
     * page is emitted because the raw gateway does not page. Callers that need
     * truncation / error / skipped-media counts use [scan(SourceRef)]; the frozen
     * signature cannot carry them (§9.4, parked for M1 by Task 20).
     */
    override fun enumerate(source: SourceRef, page: PageToken?): Flow<Page<MediaItem>> = flow {
        emit(Page(scan(source).items, null))
    }

    /**
     * The honest raw scan behind [enumerate]: bounded, media-excluding, and
     * explicit about truncation and unreadable directories.
     */
    suspend fun scan(source: SourceRef): RawScanReport = scanReport(source)

    private suspend fun scanReport(source: SourceRef): RawScanReport {
        val root = rootFor(source)
            ?: return RawScanReport(
                source = source,
                items = emptyList(),
                entriesScanned = 0,
                mediaSkipped = 0,
                mediaSkippedByExtension = 0,
                mediaSkippedByContent = 0,
                unreadableMediaSkipped = 0,
                unreadableEntries = 0,
                outOfRootEntries = 0,
                directoriesVisited = 0,
                maxDepth = 0,
                truncated = false,
                errors = emptyList(),
            )

        val items = ArrayList<MediaItem>()
        val errors = ArrayList<RawScanError>()
        var scanned = 0
        var mediaSkipped = 0
        var mediaSkippedByExtension = 0
        var mediaSkippedByContent = 0
        var unreadableMediaSkipped = 0
        var unreadable = 0
        var outOfRoot = 0
        var directoriesVisited = 0
        var maxDepthSeen = 0
        var truncated = false
        var capped = false

        val frontier = ArrayDeque<Node>()
        frontier.addLast(Node(root, depth = 0))
        while (frontier.isNotEmpty() && !capped) {
            currentCoroutineContext().ensureActive()
            val node = frontier.removeFirst()
            directoriesVisited++

            val children = list(node.dir)
            if (children == null) {
                unreadable++
                errors += RawScanError(node.dir.path, "unreadable_directory")
                continue
            }

            for (child in children.sortedBy { it.name }) {
                if (scanned >= maxEntries) {
                    truncated = true
                    capped = true
                    break
                }
                scanned++
                if (child.isDirectory) {
                    // isDirectory/listFiles follow symlinks: a link can resolve
                    // outside the root, so containment is re-checked before descent.
                    val directory = RawPaths.canonical(child)
                    if (!RawPaths.isWithin(directory, root)) {
                        outOfRoot++
                        errors += RawScanError(child.path, OUTSIDE_ROOT)
                        continue
                    }
                    if (node.depth + 1 > maxDepth) {
                        truncated = true
                        continue
                    }
                    maxDepthSeen = maxOf(maxDepthSeen, node.depth + 1)
                    frontier.addLast(Node(directory, node.depth + 1))
                } else {
                    // AC-10: media is MediaStore's job; the raw gateway never
                    // serves it, and how it was classified is reported rather
                    // than hidden.
                    val verdict = classify(child)
                    if (verdict.media) {
                        mediaSkipped++
                        when (verdict.basis) {
                            MediaBasis.EXTENSION -> mediaSkippedByExtension++
                            MediaBasis.CONTENT -> mediaSkippedByContent++
                            MediaBasis.UNREADABLE -> unreadableMediaSkipped++
                            null -> Unit
                        }
                    } else {
                        items += itemFor(child, source)
                    }
                }
            }
        }

        return RawScanReport(
            source = source,
            items = items,
            entriesScanned = scanned,
            mediaSkipped = mediaSkipped,
            mediaSkippedByExtension = mediaSkippedByExtension,
            mediaSkippedByContent = mediaSkippedByContent,
            unreadableMediaSkipped = unreadableMediaSkipped,
            unreadableEntries = unreadable,
            outOfRootEntries = outOfRoot,
            directoriesVisited = directoriesVisited,
            maxDepth = maxDepthSeen,
            truncated = truncated,
            errors = errors,
        )
    }

    /**
     * [SourceScanPort]: the read-only view of the raw scan report above, in the
     * shape the frozen `Page`/`enumerate` cannot carry. The raw walk does not
     * page, so a single [ScanPage] is emitted with [ScanPage.next] `null` and any
     * supplied `page` is ignored (the raw walk produces no continuation token);
     * the report's `truncated` / `entriesScanned` become the port's progress
     * fields. It yields exactly the items [enumerate] yields and touches nothing.
     */
    override fun scan(source: SourceRef, page: PageToken?): Flow<ScanPage> = flow {
        val report = scanReport(source)
        emit(
            ScanPage(
                items = report.items,
                next = null,
                truncated = report.truncated,
                scanned = report.entriesScanned,
            ),
        )
    }

    override suspend fun metadata(item: MediaItem): Metadata {
        rejectMedia(item, "metadata")
        val file = containedFile(item)
            ?: throw FileNotFoundException("item is outside the raw roots: ${item.path ?: item.uri}")
        if (!file.exists()) throw FileNotFoundException("not found: ${file.path}")
        return Metadata(
            sizeBytes = file.length(),
            mime = RawMediaPolicy.mimeOf(file),
            lastModified = file.lastModified(),
        )
    }

    override fun openStream(item: MediaItem): InputStream {
        rejectMedia(item, "openStream")
        val file = containedFile(item)
            ?: throw FileNotFoundException("item is outside the raw roots: ${item.path ?: item.uri}")
        if (!file.exists()) throw FileNotFoundException("not found: ${file.path}")
        return FileInputStream(file)
    }

    override suspend fun move(item: MediaItem, destination: Destination): MoveResult {
        if (isMedia(item)) return MoveResult.Failed(MEDIA_NOT_PERMITTED)
        return try {
            doMove(item, destination)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            MoveResult.Failed(error.message ?: "move_failed")
        }
    }

    /**
     * The raw gateway owns no quarantine root: production wiring is
     * `ProtectedRoots → Journaling → Quarantine → Concrete`, so the Quarantine
     * decorator performs the copy → verify → delete sequence and the concrete
     * gateway's own `quarantine` is not on the production path. Mirroring
     * [SafStorageGateway], a media item is refused explicitly (AC-10) and every
     * other item is reported as not configured — nothing is touched, and no
     * item is silently dropped.
     */
    override suspend fun quarantine(items: List<MediaItem>, batch: BatchId): QuarantineResult =
        QuarantineResult.Partial(
            entries = emptyList(),
            failures = items.map { item ->
                Failure(
                    itemId = item.id,
                    reason = if (isMedia(item)) MEDIA_NOT_PERMITTED else QUARANTINE_NOT_CONFIGURED,
                )
            },
        )

    /** No quarantine store backs the raw gateway, so nothing can be restored. */
    override suspend fun restore(entry: JournalEntry): RestoreResult =
        RestoreResult.Conflict(entry.fromUri, QUARANTINE_NOT_CONFIGURED)

    override suspend fun deletePermanently(items: List<MediaItem>): DeleteResult {
        val failures = ArrayList<Failure>()
        var deleted = 0
        for (item in items) {
            if (isMedia(item)) {
                failures += Failure(item.id, MEDIA_NOT_PERMITTED)
                continue
            }
            val file = containedFile(item)
            if (file == null) {
                failures += Failure(item.id, "outside_roots")
                continue
            }
            // Spec §6.3: a root the user granted is never a delete target, at
            // any tier. The raw gateway applies the same rule as the SAF tier.
            if (canonicalRoots.any { it == file }) {
                failures += Failure(item.id, ROOT_DELETE_REFUSED)
                continue
            }
            if (!file.exists()) {
                failures += Failure(item.id, "not_found")
                continue
            }
            val ok = when {
                // A directory is only removable through the raw gateway when its
                // whole subtree is non-media; otherwise deleting it would route
                // media through the AC-10-exempt gateway.
                file.isDirectory && subtreeContainsMedia(file) -> {
                    failures += Failure(item.id, MEDIA_NOT_PERMITTED)
                    continue
                }
                file.isDirectory -> file.deleteRecursively()
                else -> file.delete()
            }
            if (ok) deleted++ else failures += Failure(item.id, "delete_refused")
        }
        return if (failures.isEmpty()) DeleteResult.Deleted(deleted) else DeleteResult.Partial(deleted, failures)
    }

    override suspend fun capacity(): Capacity = capacityOf(canonicalRoots.first())

    /** Raw paths offer no change notifications; a watch yields nothing. */
    override fun watch(source: SourceRef): Flow<ChangeEvent> = emptyFlow()

    /**
     * [ItemLocator]: a raw item's `uri`/`path` is the real absolute filesystem
     * path the gateway addresses it by, so existence is [File.exists].
     */
    override suspend fun exists(uri: String): Boolean = uri.isNotBlank() && File(uri).exists()

    // --- Added capability (not part of the frozen StorageGateway) ---

    /**
     * Directories with zero entries (spec §6.4 `EMPTY_FOLDER`), across every
     * root. A directory is excluded when it is:
     * - a root itself (a granted root is never an empty-folder candidate);
     * - marked by a `.nomedia` marker, either on itself or on an ancestor inside
     *   the root, or
     * - named like a known app path ([KNOWN_APP_DIR_NAMES]).
     *
     * A directory containing only an empty subdirectory is **not** empty — it
     * has one entry — while the subdirectory itself is reported. An unreadable
     * directory is not provably empty and is never reported.
     */
    suspend fun emptyFolders(): List<File> {
        val empty = ArrayList<File>()
        for (root in canonicalRoots) {
            val frontier = ArrayDeque<Node>()
            frontier.addLast(Node(root, depth = 0))
            while (frontier.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                val node = frontier.removeFirst()
                val children = list(node.dir) ?: continue
                val hidden = node.hiddenByNoMedia || children.any { it.name == NO_MEDIA }
                if (children.isEmpty() && node.depth > 0 && !hidden && !isKnownAppPath(node.dir)) {
                    empty += RawPaths.canonical(node.dir)
                }
                for (child in children) {
                    if (!child.isDirectory) continue
                    // Never descend through a link that leaves the root.
                    val directory = RawPaths.canonical(child)
                    if (!RawPaths.isWithin(directory, root)) continue
                    frontier.addLast(Node(directory, node.depth + 1, hidden))
                }
            }
        }
        return empty.sortedBy { it.path }
    }

    // --- Internals ---

    /** One queued directory, flattened so the walk carries no closure state. */
    private data class Node(val dir: File, val depth: Int, val hiddenByNoMedia: Boolean = false)

    private fun doMove(item: MediaItem, destination: Destination): MoveResult {
        val sourceFile = containedFile(item) ?: return MoveResult.Failed("outside_roots")
        if (!sourceFile.exists()) return MoveResult.Failed("source_not_found")
        if (sourceFile.isDirectory) return MoveResult.Failed("refusing_to_move_a_directory")
        if (RawMediaPolicy.isMediaFile(sourceFile)) return MoveResult.Failed(MEDIA_NOT_PERMITTED)

        val destinationRoot = rootFor(destination.source)
            ?: return MoveResult.Failed("destination_not_a_raw_root")
        val destinationDir = resolveDirectory(destinationRoot, destination.relativePath)
            ?: return MoveResult.Failed("destination_not_found")

        // An unlistable destination must not look empty: renameTo could overwrite
        // on a platform that permits it. Fail explicitly instead.
        val existing = list(destinationDir)
            ?: return MoveResult.Failed(DESTINATION_UNREADABLE)
        val names = existing.mapTo(mutableSetOf()) { it.name }
        val collision = sourceFile.name in names
        val targetName = if (collision) SafStorageGateway.freeName(names, sourceFile.name) else sourceFile.name
        val target = File(destinationDir, targetName)

        val moved = if (sourceFile.renameTo(target)) true else copyThenDelete(sourceFile, target)
        if (!moved) return MoveResult.Failed("move_failed")

        val movedItem = itemFor(target, destination.source)
        return if (collision) MoveResult.NameCollision(movedItem) else MoveResult.Moved(movedItem)
    }

    /** Rename across a filesystem boundary is refused by the kernel; fall back to copy → delete. */
    private fun copyThenDelete(source: File, target: File): Boolean = try {
        source.inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output) } }
        if (target.length() == source.length() && source.delete()) {
            true
        } else {
            runCatching { target.delete() }
            false
        }
    } catch (error: Throwable) {
        runCatching { target.delete() }
        false
    }

    private fun subtreeContainsMedia(directory: File): Boolean {
        val frontier = ArrayDeque<File>()
        frontier.addLast(directory)
        while (frontier.isNotEmpty()) {
            // Unreadability is treated as media-bearing, not as a dead end. If a
            // directory cannot be listed the gateway cannot see whether it holds
            // media, so `deleteRecursively()` would route that hidden media
            // through the AC-10-exempt raw path. Refuse instead, matching the
            // file-level "unreadable means media" rule in [RawMediaPolicy].
            val children = list(frontier.removeFirst()) ?: return true
            for (child in children) {
                if (child.isDirectory) {
                    val canonical = RawPaths.canonical(child)
                    if (canonicalRoots.any { RawPaths.isWithin(canonical, it) }) frontier.addLast(canonical)
                } else if (classify(child).media) {
                    return true
                }
            }
        }
        return false
    }

    /** The content-aware AC-10 verdict for [file], through the injected byte reader. */
    private fun classify(file: File): MediaVerdict = RawMediaPolicy.classify(file, readBytes)

    /**
     * A media item: by MIME prefix, by media extension, **or** by magic-byte
     * sniff (AC-10). A path outside every root falls back to its extension,
     * since its bytes cannot be read through this gateway anyway.
     */
    private fun isMedia(item: MediaItem): Boolean {
        if (RawMediaPolicy.isMediaMime(item.mime)) return true
        val path = item.path ?: item.uri
        val file = containedFile(item)
        return if (file == null) RawMediaPolicy.isMediaName(File(path).name) else classify(file).media
    }

    private fun rejectMedia(item: MediaItem, entryPoint: String) {
        if (isMedia(item)) {
            throw MediaNotPermittedException(
                "AC-10: $entryPoint must not serve media; the raw gateway is for non-media " +
                    "management only (spec §4.2 condition 2, §4.6). Item: ${item.path ?: item.uri}",
            )
        }
    }

    /** The canonical file an item names, or null when it escapes every root. */
    private fun containedFile(item: MediaItem): File? {
        val path = item.path ?: item.uri
        if (path.isBlank()) return null
        val file = runCatching { File(path).canonicalFile }.getOrElse { return null }
        return if (canonicalRoots.any { file.isWithin(it) }) file else null
    }

    /** Resolves a destination directory, refusing any path that escapes its root. */
    private fun resolveDirectory(root: File, relativePath: String?): File? {
        val segments = relativePath?.trim('/')?.split('/')?.filter { it.isNotEmpty() }.orEmpty()
        if (segments.any { it == ".." }) return null
        var directory = root
        for (segment in segments) directory = File(directory, segment)
        val canonical = runCatching { directory.canonicalFile }.getOrElse { return null }
        if (!canonical.isWithin(root)) return null
        return if (canonical.isDirectory) canonical else null
    }

    private fun itemFor(file: File, source: SourceRef): MediaItem {
        val root = rootFor(source) ?: canonicalRoots.first()
        val relative = file.relativeTo(root)
        return MediaItem(
            id = ItemId("${source.id.value}:$relative"),
            uri = file.path,
            path = file.path,
            relativePath = relative,
            mediaStoreId = null,
            mime = RawMediaPolicy.mimeOf(file),
            sizeBytes = file.length(),
            width = null,
            height = null,
            durationMs = null,
            dateAdded = 0L,
            dateModified = file.lastModified(),
            bucket = file.parentFile?.takeIf { it != root }?.name,
            sourceId = source.id,
            hash = null,
        )
    }

    /** Lists a directory through the injected seam; null means unreadable. */
    private fun list(directory: File): List<File>? = try {
        listChildren(directory)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        null
    }

    private fun rootFor(source: SourceRef): File? =
        canonicalRoots.firstOrNull { sourceIdFor(it) == source.id }

    private fun sourceFor(root: File): SourceRef {
        val id = sourceIdFor(root)
        return SourceRef(
            id = id,
            treeUri = root.path,
            displayName = root.name.ifBlank { root.path },
            access = SourceRef.Access.RW,
            kind = SourceKind.RAW,
            itemCount = null,
            totalBytes = null,
            grantedAt = 0L,
        )
    }

    private fun sourceIdFor(root: File): SourceId = SourceId("raw:${root.path}")

    private fun validatedRoot(root: File): File {
        require(root.exists()) { "RawStorageGateway root does not exist: ${root.path}" }
        require(root.isDirectory) { "RawStorageGateway root is not a directory: ${root.path}" }
        val canonical = canonical(root)
        require(canonical !in FILESYSTEM_ROOTS) {
            "a filesystem root cannot be a RawStorageGateway root: ${canonical.path}"
        }
        return canonical
    }

    private fun isKnownAppPath(directory: File): Boolean = directory.name.lowercase() in KNOWN_APP_DIR_NAMES

    private fun canonical(file: File): File = RawPaths.canonical(file)

    private fun File.isWithin(root: File): Boolean = RawPaths.isWithin(this, root)

    private fun File.relativeTo(root: File): String =
        path.removePrefix(root.path).trimStart('\\', '/').replace('\\', '/')

    companion object {
        /** Default depth cap: 32 levels below a root, matching the SAF walker. */
        const val DEFAULT_MAX_DEPTH: Int = 32

        /** Default entry cap per scan: 200,000 entries, matching the SAF walker. */
        const val DEFAULT_MAX_ENTRIES: Int = 200_000

        /** The marker that hides a directory from empty-folder detection (§6.4). */
        const val NO_MEDIA: String = ".nomedia"

        /** Explicit AC-10 refusal reason. */
        const val MEDIA_NOT_PERMITTED: String = "media_not_permitted"

        /** Explicit refusal when no quarantine store backs the raw gateway. */
        const val QUARANTINE_NOT_CONFIGURED: String = "quarantine_not_configured"

        /** A directory child that canonicalises outside the root being walked. */
        const val OUTSIDE_ROOT: String = "outside_root"

        /** A declared root is never a delete target (spec §6.3). */
        const val ROOT_DELETE_REFUSED: String = "refusing_to_delete_a_root"

        /** A move destination that cannot be listed is refused, not assumed empty. */
        const val DESTINATION_UNREADABLE: String = "destination_unreadable"

        /**
         * Directory names the raw gateway never reports as empty (§6.4 "not a
         * known app path"). Name-based by design: the scanner cannot enumerate
         * installed packages, so it conservatively excludes the Android
         * app-storage subtrees, the OS thumbnail cache and trash directories.
         */
        val KNOWN_APP_DIR_NAMES: Set<String> = setOf(
            "android",
            "data",
            "obb",
            "media",
            ".thumbnails",
            ".thumbnail",
            "lost.dir",
            ".trash",
            ".trashed",
        )

        private val FILESYSTEM_ROOTS: List<File> by lazy {
            File.listRoots().map { root ->
                runCatching { root.canonicalFile }.getOrElse { root.absoluteFile }
            }
        }

        /**
         * Builds the gateway only when the device currently holds All Files
         * Access. Returns `null` otherwise, so Task 24's DI graph can refuse to
         * provide it at Tier 0/1.
         *
         * [hasAllFilesAccess] is injectable so a host test never touches the real
         * `Environment` state; production uses
         * `Environment.isExternalStorageManager()` guarded by the API-30 check.
         */
        fun fromEnvironment(
            roots: List<File>,
            hasAllFilesAccess: () -> Boolean = {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()
            },
        ): RawStorageGateway? =
            if (hasAllFilesAccess()) RawStorageGateway(roots = roots, hasAllFilesAccess = true) else null
    }
}

/**
 * The production capacity probe behind [RawStorageGateway.capacity]. Kept a
 * top-level function so the constructor can default to it while tests inject a
 * fake — `StatFs` cannot run on the host JVM.
 */
fun statFsCapacity(file: File): Capacity = try {
    val stats = StatFs(file.absolutePath)
    val total = stats.totalBytes
    val free = stats.availableBytes
    Capacity(totalBytes = total, usedBytes = total - free, freeBytes = free, reclaimableBytes = 0L)
} catch (error: Throwable) {
    Capacity(totalBytes = 0L, usedBytes = 0L, freeBytes = 0L, reclaimableBytes = 0L)
}
