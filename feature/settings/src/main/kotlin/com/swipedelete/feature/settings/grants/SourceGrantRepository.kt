package com.swipedelete.feature.settings.grants

import com.swipedelete.core.common.Clock
import com.swipedelete.core.model.SourceId
import com.swipedelete.core.model.SourceRef
import com.swipedelete.core.storage.saf.PersistedGrantStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * The app's grant policy: the cap, the ordering the grants screen shows, and
 * revoke (SPEC 4.5 and its `[OPEN]` cap, resolved as `MAX_GRANTS = 20` by V2).
 *
 * It is constructed **without Android** — no `Context`, no `ContentResolver`,
 * and no `Uri` in its API — because every rule it enforces is testable with
 * fake ports, while the platform work lives behind [PersistedGrantStore] and
 * [GrantMutator]. `persist` therefore takes the tree URI as the string the
 * mutator mapped from the picker's `Uri`.
 *
 * [MAX_GRANTS] is a product cap, not the OS one: the platform allows 512
 * persisted grants on API 30+ / 128 on API 26-29 and *silently evicts the
 * oldest* when that is exceeded. Enforcing the smaller cap here means the 21st
 * grant is refused with [PersistResult.GrantCapReached] before the platform is
 * asked, so the existing grants are provably untouched.
 *
 * @param grants the read port the platform owns.
 * @param mutator the write port; the only collaborator that touches a `Uri`.
 * @param clock stamps a grant whose platform `persistedTime` is unavailable, so
 *   ordering stays meaningful across providers.
 */
class SourceGrantRepository(
    private val grants: PersistedGrantStore,
    private val mutator: GrantMutator,
    private val clock: Clock,
) {

    /** Bumped on every mutation so an active collector of [grants] re-reads. */
    private val revision = MutableStateFlow(0)

    /** The cap (V2). */
    val maxGrants: Int get() = MAX_GRANTS

    /** The persisted grants, oldest first — the order the grants screen shows. */
    fun grants(): Flow<List<SourceRef>> = revision.map {
        grants.sources().sortedBy { source -> source.grantedAt }
    }

    /**
     * Persists a tree grant, unless the cap is already reached. A refusal does
     * not touch the mutator or the platform, so a rejected 21st grant cannot
     * evict an existing one.
     */
    suspend fun persist(treeUri: String, flags: Int): PersistResult {
        if (grants.sources().size >= MAX_GRANTS) return PersistResult.GrantCapReached
        val result = mutator.persist(treeUri, flags)
        if (result is PersistResult.Persisted) revision.update { it + 1 }
        return stamped(result)
    }

    /** Releases the grant for [id]; no file inside the tree is touched. */
    suspend fun revoke(id: SourceId) {
        mutator.revoke(id)
        revision.update { it + 1 }
    }

    private fun stamped(result: PersistResult): PersistResult =
        if (result is PersistResult.Persisted && result.source.grantedAt <= 0L) {
            PersistResult.Persisted(result.source.copy(grantedAt = clock.nowMillis()))
        } else {
            result
        }

    companion object {
        /** The app's grant cap (SPEC 4.5 `[OPEN]`, V2). */
        const val MAX_GRANTS: Int = 20
    }
}
