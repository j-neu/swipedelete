package com.swipedelete.core.storage.saf

import com.swipedelete.core.model.ItemId
import com.swipedelete.core.model.MediaItem
import com.swipedelete.core.model.SourceId
import com.swipedelete.core.model.SourceRef
import com.swipedelete.core.storage.PageToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One page of a tree walk: the files found, plus how to continue and how honest the page is. */
data class WalkPage(
    val items: List<MediaItem>,
    val next: PageToken?,
    val progress: WalkProgress,
)

/**
 * Cumulative scan progress. [truncated] is the explicit "this walk is
 * incomplete" flag (§9.4): set when the depth or entry cap stopped the scan, it
 * must be shown to the user rather than the partial list being presented as
 * complete. [errors] are the failures encountered while producing *this* page;
 * [unreadableEntries] is the cumulative count.
 */
data class WalkProgress(
    val entriesScanned: Int,
    val unreadableEntries: Int,
    val maxDepth: Int,
    val truncated: Boolean,
    val errors: List<WalkError>,
)

/** One document the walk could not read, with why. Never silently dropped. */
data class WalkError(
    val documentId: String,
    val relativePath: String?,
    val reason: String,
)

/**
 * A bounded, paged, cancellable, resumable walk of a SAF tree.
 *
 * Breadth-first with a deterministic frontier, so the same tree and the same
 * page size always produce the same pages. Three guarantees back §9.4:
 *
 * - **Bounded.** [maxDepth] (default [DEFAULT_MAX_DEPTH]) stops descent and
 *   [maxEntries] (default [DEFAULT_MAX_ENTRIES]) stops the scan; either sets
 *   [WalkProgress.truncated] explicitly. A walk is never silently incomplete.
 * - **Resumable.** Every non-final page carries a [PageToken] that fully
 *   encodes the frontier and counters, so resuming from it reproduces exactly
 *   the remaining items in order (no gap, no repeat).
 * - **Honest.** A child that cannot be listed or whose metadata cannot be read
 *   is counted in [WalkProgress.unreadableEntries] and surfaced in
 *   [WalkProgress.errors] — never dropped as if it did not exist.
 *
 * Cancellation is checked between every document, so a cancelled collection
 * stops promptly and the pages already emitted stay valid.
 */
class DocumentTreeWalker(
    private val access: DocumentTreeAccess,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
    private val maxDepth: Int = DEFAULT_MAX_DEPTH,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {

    init {
        require(pageSize > 0) { "pageSize must be positive, was $pageSize" }
        require(maxDepth >= 0) { "maxDepth must not be negative, was $maxDepth" }
        require(maxEntries > 0) { "maxEntries must be positive, was $maxEntries" }
    }

    /**
     * Walks [source] from [rootDocumentId]. [start] continues an earlier walk;
     * a token that belongs to a different tree/source/root is rejected rather
     * than silently mis-resumed.
     */
    fun walk(
        source: SourceRef,
        rootDocumentId: String = SafUri.treeDocumentId(source.treeUri),
        start: PageToken? = null,
    ): Flow<WalkPage> = flow {
        val state = start?.let { decodeToken(it, source, rootDocumentId) }
            ?: MutableState.initial(source, rootDocumentId)
        var truncationSurfaced = false

        while (true) {
            val items = ArrayList<MediaItem>(minOf(pageSize, INITIAL_PAGE_CAPACITY))
            val errors = ArrayList<WalkError>()

            while (items.size < pageSize && state.frontier.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                val frame = state.frontier.removeAt(0)

                if (frame.directory) {
                    if (state.capped) continue
                    if (frame.depth >= maxDepth) {
                        // The cap is what stops the descent; there may be more below.
                        state.truncated = true
                        continue
                    }
                    val children = try {
                        access.children(state.treeUri, frame.documentId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        state.unreadable++
                        errors += WalkError(frame.documentId, frame.relativePath, reasonFor(error))
                        continue
                    }
                    for (child in children) {
                        if (state.scanned >= maxEntries) {
                            state.truncated = true
                            state.capped = true
                            break
                        }
                        state.scanned++
                        state.maxDepth = maxOf(state.maxDepth, frame.depth + 1)
                        state.frontier += frame.child(child)
                    }
                } else {
                    val node = DocumentNode(
                        treeUri = state.treeUri,
                        documentId = frame.documentId,
                        displayName = frame.displayName,
                        mime = frame.mime,
                        sizeBytes = 0L,
                        lastModified = 0L,
                        isDirectory = false,
                    )
                    val meta = try {
                        access.metadata(node)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        state.unreadable++
                        errors += WalkError(frame.documentId, frame.relativePath, reasonFor(error))
                        continue
                    }
                    items += state.item(frame, meta)
                }
            }

            val done = state.frontier.isEmpty()
            val progress = WalkProgress(
                entriesScanned = state.scanned,
                unreadableEntries = state.unreadable,
                maxDepth = state.maxDepth,
                truncated = state.truncated,
                errors = errors,
            )
            // A cap discovered on an otherwise-empty final step must still be
            // surfaced: an uncapped page is emitted, and the explicit truncated
            // flag can never be swallowed with it. A normal completed walk emits
            // no trailing empty page.
            val mustSurfaceTruncation = state.truncated && !truncationSurfaced
            if (items.isEmpty() && errors.isEmpty() && done && !mustSurfaceTruncation) break
            if (state.truncated) truncationSurfaced = true

            emit(WalkPage(items, if (done) null else state.token(), progress))
            if (done) break
        }
    }

    private fun reasonFor(error: Throwable): String {
        val message = error.message
        return if (message.isNullOrBlank()) {
            error::class.simpleName ?: "unreadable"
        } else {
            "${error::class.simpleName}: $message"
        }
    }

    private fun decodeToken(token: PageToken, source: SourceRef, rootDocumentId: String): MutableState {
        val decoded = try {
            json.decodeFromString(WalkerToken.serializer(), token.value)
        } catch (error: Throwable) {
            throw IllegalArgumentException("malformed page token", error)
        }
        require(
            decoded.treeUri == source.treeUri &&
                decoded.sourceId == source.id.value &&
                decoded.rootDocumentId == rootDocumentId
        ) {
            "page token belongs to a different tree/source/root and must not be resumed here"
        }
        return MutableState(
            treeUri = decoded.treeUri,
            sourceId = decoded.sourceId,
            rootDocumentId = decoded.rootDocumentId,
            frontier = decoded.frontier.mapTo(ArrayDeque()) { it.toFrame() },
            scanned = decoded.scanned,
            unreadable = decoded.unreadable,
            maxDepth = decoded.maxDepth,
            truncated = decoded.truncated,
            capped = decoded.capped,
        )
    }

    private class MutableState(
        val treeUri: String,
        val sourceId: String,
        val rootDocumentId: String,
        val frontier: ArrayDeque<Frame>,
        var scanned: Int,
        var unreadable: Int,
        var maxDepth: Int,
        var truncated: Boolean,
        var capped: Boolean,
    ) {
        fun item(frame: Frame, meta: DocumentMetadata): MediaItem {
            val uri = SafUri.join(treeUri, frame.relativePath)
            return MediaItem(
                // `sourceId:relativePath`, matching `RawStorageGateway.itemFor` and
                // the frozen `StorageGatewayContractTest` fixture (see
                // `SafStorageGateway.itemAt` for why the separator matters).
                id = ItemId("$sourceId:${frame.relativePath}"),
                uri = uri,
                path = uri,
                relativePath = frame.relativePath,
                mediaStoreId = null,
                mime = meta.mime,
                sizeBytes = meta.sizeBytes,
                width = null,
                height = null,
                durationMs = null,
                dateAdded = 0L,
                dateModified = meta.lastModified,
                bucket = frame.relativePath.substringBeforeLast('/', "").ifEmpty { null },
                sourceId = SourceId(sourceId),
                hash = null,
            )
        }

        fun token(): PageToken = PageToken(
            json.encodeToString(
                WalkerToken.serializer(),
                WalkerToken(
                    treeUri = treeUri,
                    sourceId = sourceId,
                    rootDocumentId = rootDocumentId,
                    frontier = frontier.map { it.toToken() },
                    scanned = scanned,
                    unreadable = unreadable,
                    maxDepth = maxDepth,
                    truncated = truncated,
                    capped = capped,
                ),
            )
        )

        companion object {
            fun initial(source: SourceRef, rootDocumentId: String): MutableState = MutableState(
                treeUri = source.treeUri,
                sourceId = source.id.value,
                rootDocumentId = rootDocumentId,
                frontier = ArrayDeque(
                    listOf(
                        Frame(
                            documentId = rootDocumentId,
                            displayName = "",
                            mime = NEVER_LISTED,
                            directory = true,
                            depth = 0,
                            relativePath = "",
                        ),
                    ),
                ),
                scanned = 0,
                unreadable = 0,
                maxDepth = 0,
                truncated = false,
                capped = false,
            )
        }
    }

    /** One queued document, flattened so it can be serialised into a token. */
    private data class Frame(
        val documentId: String,
        val displayName: String,
        val mime: String,
        val directory: Boolean,
        val depth: Int,
        val relativePath: String,
    ) {
        fun child(node: DocumentNode) = Frame(
            documentId = node.documentId,
            displayName = node.displayName,
            mime = node.mime,
            directory = node.isDirectory,
            depth = depth + 1,
            relativePath = if (relativePath.isEmpty()) node.displayName else "$relativePath/${node.displayName}",
        )

        fun toToken() = FrameToken(documentId, displayName, mime, directory, depth, relativePath)
    }

    @Serializable
    private data class FrameToken(
        val documentId: String,
        val displayName: String,
        val mime: String,
        val directory: Boolean,
        val depth: Int,
        val relativePath: String,
    ) {
        fun toFrame() = Frame(documentId, displayName, mime, directory, depth, relativePath)
    }

    @Serializable
    private data class WalkerToken(
        val treeUri: String,
        val sourceId: String,
        val rootDocumentId: String,
        val frontier: List<FrameToken>,
        val scanned: Int,
        val unreadable: Int,
        val maxDepth: Int,
        val truncated: Boolean,
        val capped: Boolean,
    )

    companion object {
        /** Default page size for a SAF walk. */
        const val DEFAULT_PAGE_SIZE: Int = 200

        /** Default depth cap: 32 levels below the granted tree root. */
        const val DEFAULT_MAX_DEPTH: Int = 32

        /** Hard entry cap per tree: 200,000 documents. */
        const val DEFAULT_MAX_ENTRIES: Int = 200_000

        private const val INITIAL_PAGE_CAPACITY = 64
        private const val NEVER_LISTED = "application/octet-stream"

        private val json = Json { ignoreUnknownKeys = true }
    }
}
