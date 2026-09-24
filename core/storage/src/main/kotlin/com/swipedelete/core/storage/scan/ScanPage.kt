package com.swipedelete.core.storage.scan

import com.swipedelete.core.model.MediaItem
import com.swipedelete.core.storage.Page
import com.swipedelete.core.storage.PageToken

/**
 * A page of a scan, carrying the progress and truncation the frozen [Page]
 * cannot.
 *
 * The frozen `StorageGateway.enumerate` returns a `Page<T>(items, next)` whose
 * shape has no room for the honest per-page progress spec §9.4 requires, and
 * M0 deliberately left that capability unreachable rather than widen the frozen
 * contract (`docs/M0-ACCEPTANCE.md`, Finding 1). This is the M1-owned read-only
 * seam that carries it.
 *
 * @property items the items found in this page, in the source's stable order.
 * @property next the token that resumes the scan, or `null` on the final page.
 * @property truncated true once a walk cap (depth or entry count) stopped the
 *   scan. A truncated page is never the whole source: the caller must present
 *   it as partial rather than as a complete listing.
 * @property scanned the cumulative number of entries the walk has examined so
 *   far, so a progress indicator is honest about how much was looked at, not
 *   only about how much matched.
 */
data class ScanPage(
    val items: List<MediaItem>,
    val next: PageToken?,
    val truncated: Boolean,
    val scanned: Int,
)
