package com.swipedelete.core.storage.scan

import com.swipedelete.core.model.SourceRef
import com.swipedelete.core.storage.PageToken
import kotlinx.coroutines.flow.Flow

/**
 * A **read-only** view of a source's walk, for scan progress and truncation
 * (D1, `docs/M1-PLAN.md`).
 *
 * The frozen [com.swipedelete.core.storage.StorageGateway] cannot carry this:
 * its `enumerate` page has no progress or truncation field, and the concrete
 * walkers that do (`SafStorageGateway.enumerateDetailed`,
 * `RawStorageGateway.scan`) are unreachable through the decorated gateway M0
 * makes injectable. This port exposes exactly that extra information, and
 * nothing else.
 *
 * Guarantees:
 * - **Read-only.** [scan] never mutates, never writes a journal row and never
 *   moves a file. It is not a decorator and does not sit on the mutating path
 *   (`ProtectedRoots → Journaling → Quarantine → concrete`), so it does not
 *   reopen the AC-1/AC-7 bypass the handle closes.
 * - **Consistent with the frozen surface.** For a given source, the items
 *   [scan] yields are exactly the items `StorageGateway.enumerate` yields; only
 *   the extra progress/truncation fields differ.
 * - **Stably ordered.** The same source produces the same order across calls,
 *   and a non-final page's [ScanPage.next] resumes the walk with no gap and no
 *   repeat.
 *
 * Implementations: [com.swipedelete.core.storage.saf.SafStorageGateway] (maps
 * the SAF walker's `WalkPage`) and
 * [com.swipedelete.core.storage.raw.RawStorageGateway] (maps its own scan
 * report). A future concrete gateway that cannot scan (e.g. a MediaStore-only
 * build) simply does not implement this port.
 */
interface SourceScanPort {

    /**
     * Scans [source], continuing from [page] when non-null. A single call
     * emits one [ScanPage] per underlying walk page; the last page has
     * [ScanPage.next] `null` and carries the final [ScanPage.truncated] /
     * [ScanPage.scanned] values.
     */
    fun scan(source: SourceRef, page: PageToken? = null): Flow<ScanPage>
}
