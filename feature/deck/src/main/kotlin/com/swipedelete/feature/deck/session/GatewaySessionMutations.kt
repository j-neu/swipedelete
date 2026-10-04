package com.swipedelete.feature.deck.session

import com.swipedelete.core.model.BatchId
import com.swipedelete.core.model.MediaItem
import com.swipedelete.core.model.SessionId
import com.swipedelete.core.storage.QuarantineResult
import com.swipedelete.core.storage.StorageGateway
import com.swipedelete.feature.deck.domain.staleReason as staleReasonFor

/**
 * The production [SessionMutations] over the journaled [StorageGateway].
 *
 * [staleReason] is what makes AC-8 real in the shipped app: before an item is
 * destroyed, its *current* metadata is re-read through the gateway and compared
 * with the frozen snapshot. A changed (or vanished) item returns a user-visible
 * reason, so the session aborts it instead of deleting the wrong bytes.
 *
 * [restoreSession] is a documented gap: the M1 journal does not yet tag rows
 * with a session id (`JournalingStorageGateway` writes `sessionId = null`), so
 * there is no query that can enumerate one session's quarantine entries. The
 * panic button is wired end-to-end but restores nothing until the journal
 * carries the session link (tracked in the ledger as M1-T15 ruling).
 */
class GatewaySessionMutations(
    private val gateway: StorageGateway,
) : SessionMutations {

    override suspend fun quarantine(items: List<MediaItem>, batch: BatchId): Int =
        when (val result = gateway.quarantine(items, batch)) {
            is QuarantineResult.Quarantined -> result.entries.size
            is QuarantineResult.Partial -> result.entries.size
            is QuarantineResult.InsufficientSpace -> 0
        }

    override suspend fun restoreSession(session: SessionId): Int = 0

    override suspend fun staleReason(item: MediaItem): String? {
        val actual = try {
            gateway.metadata(item)
        } catch (error: Exception) {
            return "No longer readable: ${error.message ?: "the item could not be read"}"
        }
        return staleReasonFor(item, actual)
    }
}
