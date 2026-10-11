package com.swipedelete.core.storage

import com.swipedelete.core.model.SourceId
import java.io.InputStream

/**
 * A concrete gateway that can put a quarantined item back where it came from
 * when that place is not a raw filesystem path (AC-3).
 *
 * [com.swipedelete.core.storage.quarantine.QuarantineStore] restores a raw
 * original itself. A folder-grant (SAF) original is only reachable through its
 * document tree, so the store hands the write to the gateway that owns the tree
 * and keeps everything else: the hash check before, and removing the quarantine
 * copy only after a [OriginalRestoreOutcome.Restored].
 *
 * An implementation must never overwrite (AC-17) and must verify what it wrote
 * against [OriginalRestoreRequest.sha256] before reporting success.
 */
interface OriginalRestorer {
    suspend fun restoreOriginal(request: OriginalRestoreRequest): OriginalRestoreOutcome
}

/** Everything the sidecar recorded about where the item lived, plus its bytes. */
class OriginalRestoreRequest(
    val sourceId: SourceId,
    val originalUri: String,
    val relativePath: String?,
    val mediaStoreId: Long?,
    val mime: String,
    val sha256: String,
    /** Opens the verified quarantine copy; the caller of [open] closes the stream. */
    val open: () -> InputStream,
)

sealed interface OriginalRestoreOutcome {
    /** The item is back at [uri], verified. */
    data class Restored(val uri: String) : OriginalRestoreOutcome

    /** Something else is at the original place; nothing was written. */
    data class Occupied(val uri: String) : OriginalRestoreOutcome

    /** The restore could not be done; nothing is left behind. [reason] is a stable code. */
    data class Unavailable(val reason: String) : OriginalRestoreOutcome
}
