package com.swipedelete.core.storage

import com.swipedelete.core.model.BatchId
import com.swipedelete.core.model.Capacity
import com.swipedelete.core.model.JournalEntry
import com.swipedelete.core.model.MediaItem
import com.swipedelete.core.model.SourceRef
import kotlinx.coroutines.flow.Flow
import java.io.InputStream

/**
 * The single seam through which every file operation in the app passes.
 *
 * Implementations must honour the following invariants (the frozen contract in
 * [com.swipedelete.core.storage.StorageGatewayContractTest]):
 * - [move] and [restore] never overwrite an existing file. A colliding name is
 *   either renamed (`a (1).gif`) or reported as [MoveResult.NameCollision] /
 *   [RestoreResult.Conflict] with nothing touched.
 * - [quarantine] moves each item atomically (all-or-nothing per item). A
 *   cancellation between items leaves the not-yet-moved item at its source and
 *   the quarantine store unchanged.
 * - [enumerate] yields a stable ordering for the same source across calls.
 */
interface StorageGateway {
    suspend fun sources(): List<SourceRef>
    fun enumerate(source: SourceRef, page: PageToken?): Flow<Page<MediaItem>>
    suspend fun metadata(item: MediaItem): Metadata
    fun openStream(item: MediaItem): InputStream
    suspend fun move(item: MediaItem, destination: Destination): MoveResult
    suspend fun quarantine(items: List<MediaItem>, batch: BatchId): QuarantineResult
    suspend fun restore(entry: JournalEntry): RestoreResult
    suspend fun deletePermanently(items: List<MediaItem>): DeleteResult
    suspend fun capacity(): Capacity
    fun watch(source: SourceRef): Flow<ChangeEvent>
}
