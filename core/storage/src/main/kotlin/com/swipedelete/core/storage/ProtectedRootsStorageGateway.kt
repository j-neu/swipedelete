package com.swipedelete.core.storage

import com.swipedelete.core.model.BatchId
import com.swipedelete.core.model.ItemId
import com.swipedelete.core.model.JournalEntry
import com.swipedelete.core.model.MediaItem
import kotlinx.coroutines.currentCoroutineContext
import java.net.URLDecoder

/**
 * AC-7: the outermost decorator in the frozen stack
 * `ProtectedRoots → Journaling → Quarantine → Concrete`.
 *
 * Every mutating call filters protected items out **before** the delegate runs,
 * so a protected item can never reach a journal write or a file operation. A
 * refusal is always explicit — a [Failure] with reason `protected_root` whose
 * `cause` is the matched [ProtectedRoot.pattern] — never a silent drop.
 *
 * [restore] is deliberately **not** filtered: restoring a quarantined file puts
 * it back where it came from, which may legitimately be inside `DCIM/`.
 * Blocking it would trap the user's files in quarantine forever (AC-3).
 *
 * Read-only calls pass straight through (via interface delegation).
 *
 * @param delegate the next gateway in the stack.
 * @param unlocked the pattern strings the user has explicitly unlocked; an
 *   unlocked pattern is simply not applied, while every other pattern still is.
 * @param isRoot true when the item *is* the SAF tree root the user granted.
 * @param quarantineRoot the app's own quarantine directory, or null if unknown;
 *   when present it becomes an [ProtectedRootKind.INTERNAL] pattern.
 * @param now the clock used by the [ProtectedRootKind.AGE] rule.
 */
class ProtectedRootsStorageGateway(
    private val delegate: StorageGateway,
    private val unlocked: () -> Set<String>,
    private val isRoot: (MediaItem) -> Boolean = { false },
    private val quarantineRoot: String? = null,
    private val now: () -> Long = System::currentTimeMillis,
) : StorageGateway by delegate {

    /** True when [item] falls under any active protected root. */
    fun isProtected(item: MediaItem): Boolean = protectedBy(item) != null

    override suspend fun move(item: MediaItem, destination: Destination): MoveResult {
        if (protectedBy(item, reviewedItems()) != null) return MoveResult.Failed(REASON)
        return delegate.move(item, destination)
    }

    override suspend fun quarantine(items: List<MediaItem>, batch: BatchId): QuarantineResult {
        val (allowed, refused) = split(items, reviewedItems())
        if (allowed.isEmpty()) return QuarantineResult.Partial(emptyList(), refused)

        return when (val result = delegate.quarantine(allowed, batch)) {
            is QuarantineResult.Quarantined ->
                if (refused.isEmpty()) result
                else QuarantineResult.Partial(result.entries, refused)

            is QuarantineResult.Partial ->
                QuarantineResult.Partial(result.entries, result.failures + refused)

            // The delegate moved nothing; the refusals are the only per-item
            // outcome we can report, so the space signal is superseded here.
            is QuarantineResult.InsufficientSpace ->
                if (refused.isEmpty()) result
                else QuarantineResult.Partial(emptyList(), refused)
        }
    }

    override suspend fun deletePermanently(items: List<MediaItem>): DeleteResult {
        val (allowed, refused) = split(items)
        if (allowed.isEmpty()) return DeleteResult.Partial(0, refused)

        return when (val result = delegate.deletePermanently(allowed)) {
            is DeleteResult.Deleted ->
                if (refused.isEmpty()) result
                else DeleteResult.Partial(result.count, refused)

            is DeleteResult.Partial ->
                DeleteResult.Partial(result.deleted, result.failures + refused)
        }
    }

    /**
     * Passes straight through, even when the entry's original path is inside a
     * protected root: restore returns a file to where the user's quarantine
     * flow took it from, and refusing that would strand it in quarantine.
     */
    override suspend fun restore(entry: JournalEntry): RestoreResult = delegate.restore(entry)

    // --- Internals ---

    /**
     * The items the caller reviewed one at a time (see [IndividuallyReviewed]).
     * Permanent deletion never consults this.
     */
    private suspend fun reviewedItems(): Set<ItemId> =
        currentCoroutineContext()[IndividuallyReviewed]?.items ?: emptySet()

    private fun split(
        items: List<MediaItem>,
        reviewed: Set<ItemId> = emptySet(),
    ): Pair<List<MediaItem>, List<Failure>> {
        val allowed = ArrayList<MediaItem>(items.size)
        val refused = ArrayList<Failure>(items.size)
        items.forEach { item ->
            val root = protectedBy(item, reviewed)
            if (root == null) allowed += item else refused += Failure(item.id, REASON, root.pattern)
        }
        return allowed to refused
    }

    private fun protectedBy(item: MediaItem, reviewed: Set<ItemId> = emptySet()): ProtectedRoot? =
        activePatterns()
            .filter { item.id !in reviewed || it.kind in ALWAYS_ENFORCED }
            .firstOrNull { matches(it, item) }

    private fun activePatterns(): List<ProtectedRoot> {
        val unlockedNow = unlocked()
        return allPatterns().filter { root -> unlockedNow.none { it.trim() == root.pattern } }
    }

    private fun allPatterns(): List<ProtectedRoot> = buildList {
        addAll(DefaultProtectedRoots.patterns())
        quarantineRoot
            ?.takeIf { it.isNotBlank() }
            ?.let { add(ProtectedRoot(it, ProtectedRootKind.INTERNAL)) }
    }

    private fun matches(root: ProtectedRoot, item: MediaItem): Boolean = when (root.kind) {
        ProtectedRootKind.PATH ->
            item.searchPaths().any { PathPatternMatcher.pathMatches(it, root.pattern) }

        ProtectedRootKind.BUCKET ->
            item.bucket?.trim()?.equals(root.pattern, ignoreCase = true) == true ||
                item.searchPaths().any { PathPatternMatcher.pathMatches(it, root.pattern) }

        ProtectedRootKind.INTERNAL ->
            quarantineRoot != null && item.searchPaths().any { PathPatternMatcher.isUnder(it, quarantineRoot) }

        ProtectedRootKind.AGE -> isAgeProtected(item)

        ProtectedRootKind.SAF_ROOT -> isRoot(item)
    }

    private fun isAgeProtected(item: MediaItem): Boolean {
        val mime = item.mime.lowercase()
        val isMedia = mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/")
        return isMedia && now() - item.dateModified > DefaultProtectedRoots.AGE_THRESHOLD_MILLIS
    }

    /**
     * The path/relativePath strings to match against; falls back to the uri. A
     * folder-grant (SAF) item carries its location as an encoded document URI,
     * so the decoded document path is matched too — otherwise a tree granted at
     * `DCIM/` would hide every item under it from these rules.
     */
    private fun MediaItem.searchPaths(): List<String> =
        listOfNotNull(path, relativePath).ifEmpty { listOf(uri) }
            .flatMap { listOfNotNull(it, documentPath(it)) }

    /**
     * A content URI with its encoded document ids opened up into path segments:
     * `…/tree/primary%3ADCIM%2FAlbum/trip/a.jpg` → `…/tree/primary/DCIM/Album/trip/a.jpg`.
     * Covers both the gateway's `treeUri/relativePath` form and a real document URI.
     */
    private fun documentPath(value: String): String? {
        if (!value.startsWith("content://")) return null
        val decoded = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrNull() ?: return null
        return decoded.removePrefix("content://").replace(':', '/')
    }

    companion object {
        /** Reason attached to every refusal, so the UI can explain it. */
        const val REASON: String = "protected_root"

        /** The rules a reviewed item is still subject to. */
        private val ALWAYS_ENFORCED = setOf(ProtectedRootKind.INTERNAL, ProtectedRootKind.SAF_ROOT)
    }
}
