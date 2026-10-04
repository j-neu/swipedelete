package com.swipedelete.feature.deck.domain

import com.swipedelete.core.common.Clock
import com.swipedelete.core.common.formatBytes
import com.swipedelete.core.model.MediaItem
import com.swipedelete.core.model.SourceId
import com.swipedelete.core.model.SourceRef
import com.swipedelete.core.storage.Metadata
import com.swipedelete.core.storage.StorageGateway
import com.swipedelete.core.storage.scan.SourceScanPort
import kotlinx.coroutines.flow.collect
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Materialises a [DeckQuery] into a frozen [DeckSnapshot] (§5.1).
 *
 * Items come from the read-only [SourceScanPort] (D1), never from the mutating
 * gateway path, so building a deck can never touch a file. Ordering follows the
 * spec's defaults: oldest first for folder and age decks, largest first for
 * space decks.
 *
 * This class is pure Kotlin — no `android.*` — so deck construction is unit
 * tested without a device (D5).
 */
class DeckBuilder(
    private val scanner: (SourceRef) -> SourceScanPort,
    private val gateway: StorageGateway,
    private val clock: Clock,
) {

    suspend fun build(query: DeckQuery): DeckSnapshot {
        val builtAt = clock.nowMillis()
        val scan = when (query) {
            is DeckQuery.Folder -> folderScan(query.source)
            is DeckQuery.Smart -> smartScan(query.rule)
            // M3 bridge: a module deck's candidates come from the cleaner module,
            // which does not exist yet. The deck is honest and empty until then.
            is DeckQuery.Module -> ScanSummary(emptyList(), truncated = false, scanned = 0)
            is DeckQuery.Resume -> error(
                "Resume decks are materialised by the session layer (Task 40), not by DeckBuilder: " +
                    "the persisted query for session ${query.session.value} is not one of its injected ports.",
            )
        }
        return DeckSnapshot(
            items = scan.items,
            query = query,
            builtAt = builtAt,
            truncated = scan.truncated,
            scanned = scan.scanned,
        )
    }

    private suspend fun folderScan(source: SourceId): ScanSummary {
        val ref = gateway.sources().firstOrNull { it.id == source }
            ?: return ScanSummary(emptyList(), truncated = false, scanned = 0)
        val scan = scanAll(ref)
        return scan.copy(items = scan.items.oldestFirst())
    }

    private suspend fun smartScan(rule: SmartRule): ScanSummary {
        val scans = gateway.sources().map { scanAll(it) }
        val candidates = scans.flatMap { it.items }
        val matched = when (rule) {
            SmartRule.SCREENSHOTS -> candidates.filter { it.isScreenshot() }
            SmartRule.VIDEOS_OVER -> candidates.filter { it.isVideo() && it.sizeBytes > rule.sizeMb * BYTES_PER_MB }
            SmartRule.OLDER_THAN -> candidates.filter { clock.nowMillis() - it.dateAdded > rule.days * DAY_MILLIS }
            // The review ledger is applied by the session layer (Task 40); at build
            // every candidate is still unreviewed, so nothing is excluded yet.
            SmartRule.UNREVIEWED -> candidates
            SmartRule.LARGEST_FIRST -> candidates
        }
        return ScanSummary(
            items = if (rule.ordersLargestFirst) matched.largestFirst() else matched.oldestFirst(),
            // A deck built from several sources is partial if *any* of them was
            // capped; the caller must present it as a partial deck, not a complete
            // one (§9.4 honesty).
            truncated = scans.any { it.truncated },
            scanned = scans.sumOf { it.scanned },
        )
    }

    private suspend fun scanAll(source: SourceRef): ScanSummary {
        val items = ArrayList<MediaItem>()
        var truncated = false
        var scanned = 0
        scanner(source).scan(source).collect { page ->
            items += page.items
            truncated = truncated || page.truncated
            // `ScanPage.scanned` is cumulative, so the furthest count wins.
            scanned = maxOf(scanned, page.scanned)
        }
        return ScanSummary(items = items, truncated = truncated, scanned = scanned)
    }

    /** The scanned items plus the honest progress/truncation the frozen Page cannot carry. */
    private data class ScanSummary(val items: List<MediaItem>, val truncated: Boolean, val scanned: Int)
}

/**
 * A deck frozen at [builtAt]: the ids and metadata captured by [DeckBuilder],
 * re-checked against the disk at presentation (AC-8).
 */
data class DeckSnapshot(
    val items: List<MediaItem>,
    val query: DeckQuery,
    val builtAt: Long,
    /**
     * True when the scan hit its cap before finishing (Task 30 / §9.4): the deck
     * is missing candidates and the UI must present it as partial rather than
     * imply the library was fully seen.
     */
    val truncated: Boolean = false,
    /** How many candidates the scan examined, cumulative across every source. */
    val scanned: Int = 0,
)

/**
 * A card's "why is this here" line (§5.1), e.g.
 * `Screenshots · /Pictures/Screenshots · 4.2 MB · 14 Mar 2021`.
 *
 * Pure and deterministic: the date is rendered in UTC so the same item always
 * produces the same line.
 */
fun provenance(item: MediaItem): String {
    val label = item.bucket?.takeIf { it.isNotBlank() }
        ?: item.relativePath?.trim('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        ?: "Item"
    val location = item.relativePath?.trim('/')?.takeIf { it.isNotEmpty() }?.let { "/$it" }
        ?: item.path
        ?: item.uri
    val stamp = if (item.dateAdded > 0L) item.dateAdded else item.dateModified
    val date = Instant.ofEpochMilli(stamp).atZone(ZoneOffset.UTC).toLocalDate().format(PROVENANCE_DATE)
    return "$label · $location · ${formatBytes(item.sizeBytes)} · $date"
}

/**
 * AC-8: the reason [expected] may no longer be acted on, or `null` when the
 * disk still matches the frozen snapshot. The message is user-visible, so a
 * stale card aborts with an explanation instead of a silent no-op or a wrong
 * delete.
 *
 * The frozen [Metadata] carries no id, so an id change cannot be detected from
 * this probe alone; a vanished or replaced item surfaces through the gateway's
 * metadata read, which the presenter treats the same way (abort, do not act).
 */
fun staleReason(expected: MediaItem, actual: Metadata): String? {
    val changes = buildList {
        if (expected.sizeBytes != actual.sizeBytes) {
            add("size ${formatBytes(expected.sizeBytes)} -> ${formatBytes(actual.sizeBytes)}")
        }
        if (expected.dateModified != actual.lastModified) {
            add("modified time changed")
        }
        if (!expected.mime.equals(actual.mime, ignoreCase = true)) {
            add("type ${expected.mime} -> ${actual.mime}")
        }
        if (expected.hash != null && actual.sha256 != null && expected.hash != actual.sha256) {
            add("contents changed")
        }
    }
    return if (changes.isEmpty()) {
        null
    } else {
        "Changed on disk since the deck was built: ${changes.joinToString("; ")}"
    }
}

private const val BYTES_PER_MB = 1024L * 1024L
private const val DAY_MILLIS = 86_400_000L

private val PROVENANCE_DATE: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US)

private fun MediaItem.isVideo(): Boolean = mime.startsWith("video/", ignoreCase = true)

private fun MediaItem.isScreenshot(): Boolean {
    if (bucket?.equals("Screenshots", ignoreCase = true) == true) return true
    return relativePath?.split('/')?.any { it.equals("Screenshots", ignoreCase = true) } == true
}

private fun List<MediaItem>.oldestFirst(): List<MediaItem> = sortedWith(compareBy { it.dateAdded })

private fun List<MediaItem>.largestFirst(): List<MediaItem> = sortedWith(compareByDescending { it.sizeBytes })
