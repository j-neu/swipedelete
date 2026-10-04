package com.swipedelete.core.storage.routing

import com.swipedelete.core.model.SourceRef
import com.swipedelete.core.storage.StorageGateway
import com.swipedelete.core.storage.PageToken
import com.swipedelete.core.storage.scan.ScanPage
import com.swipedelete.core.storage.scan.SourceScanPort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * A [SourceScanPort] for a concrete gateway that can only [StorageGateway.enumerate].
 *
 * The frozen page carries no progress or truncation, so this adapter reports
 * `truncated = false` and a cumulative item count. It exists as the defensive
 * fallback for a build whose concrete gateway is not itself a scan port; the
 * production M1 graph routes through [SourceRoutingStorageGateway], which is one.
 */
class GatewayScanPort(
    private val gateway: StorageGateway,
) : SourceScanPort {

    override fun scan(source: SourceRef, page: PageToken?): Flow<ScanPage> {
        var scanned = 0
        return gateway.enumerate(source, page).map { paged ->
            scanned += paged.items.size
            ScanPage(items = paged.items, next = paged.next, truncated = false, scanned = scanned)
        }
    }
}
