package com.swipedelete.feature.deck.domain

import com.swipedelete.core.common.Clock
import com.swipedelete.core.model.ItemId
import com.swipedelete.core.model.MediaItem
import com.swipedelete.core.model.SourceId
import com.swipedelete.core.model.SourceKind
import com.swipedelete.core.model.SourceRef
import com.swipedelete.core.storage.PageToken
import com.swipedelete.core.storage.scan.ScanPage
import com.swipedelete.core.storage.scan.SourceScanPort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** One mebibyte, the unit `formatBytes` renders as "MB". */
internal const val MB: Long = 1024L * 1024L

/** One day in milliseconds, matching [SmartRule.OLDER_THAN]'s window unit. */
internal const val DAY_MS: Long = 86_400_000L

internal fun mediaItem(
    id: String,
    path: String,
    bucket: String? = null,
    relativePath: String? = null,
    mime: String = "image/jpeg",
    sizeBytes: Long = 1024L,
    dateAdded: Long = 0L,
    dateModified: Long = dateAdded,
    sourceId: SourceId = SourceId("src"),
): MediaItem = MediaItem(
    id = ItemId(id),
    uri = path,
    path = path,
    relativePath = relativePath,
    mediaStoreId = null,
    mime = mime,
    sizeBytes = sizeBytes,
    width = null,
    height = null,
    durationMs = null,
    dateAdded = dateAdded,
    dateModified = dateModified,
    bucket = bucket,
    sourceId = sourceId,
)

internal fun sourceRef(id: String, treeUri: String = "content://tree/$id"): SourceRef = SourceRef(
    id = SourceId(id),
    treeUri = treeUri,
    displayName = id,
    access = SourceRef.Access.RW,
    kind = SourceKind.SAF,
    itemCount = null,
    totalBytes = null,
    grantedAt = 0L,
)

internal fun page(
    items: List<MediaItem>,
    next: PageToken? = null,
    truncated: Boolean = false,
): ScanPage = ScanPage(items = items, next = next, truncated = truncated, scanned = items.size)

/** A [SourceScanPort] that replays canned pages and ignores the resume token. */
internal class FakeScanPort(private val pages: List<ScanPage>) : SourceScanPort {
    constructor(single: ScanPage) : this(listOf(single))

    override fun scan(source: SourceRef, page: PageToken?): Flow<ScanPage> = flow {
        pages.forEach { emit(it) }
    }
}

internal class FixedClock(private var value: Long) : Clock {
    override fun nowMillis(): Long = value

    fun advance(by: Long) {
        value += by
    }
}
