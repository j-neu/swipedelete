package com.swipedelete.feature.settings.grants

import com.swipedelete.core.common.Clock
import com.swipedelete.core.model.SourceId
import com.swipedelete.core.model.SourceRef
import com.swipedelete.core.storage.fake.FakeStorageGateway
import com.swipedelete.core.storage.saf.PersistedGrantStore

/** A fixed [Clock], so `grantedAt` stamping is deterministic. */
class FakeClock(var now: Long = 1_000L) : Clock {
    override fun nowMillis(): Long = now
}

/** In-memory read port: the persisted grants the repository counts and lists. */
class FakeGrantStore(initial: List<SourceRef> = emptyList()) : PersistedGrantStore {

    private val byId = LinkedHashMap<SourceId, SourceRef>()

    init {
        initial.forEach { byId[it.id] = it }
    }

    override suspend fun sources(): List<SourceRef> = byId.values.toList()

    fun add(source: SourceRef) {
        byId[source.id] = source
    }

    fun remove(id: SourceId) {
        byId.remove(id)
    }
}

/**
 * A recording [GrantMutator] that stands in for `ContentResolverGrantStore`.
 *
 * It holds the [gateway] on purpose: the gateway is the only collaborator in
 * this test that *could* touch a file, so "revoke recorded a release and the
 * gateway stayed at zero operations" is a real assertion about the revoke path
 * rather than a tautology.
 *
 * The display name / access mapping is the production [GrantUriMapping], so the
 * "display name and access mode" assertions test shipped code, not the fake.
 */
class FakeGrantMutator(
    private val store: FakeGrantStore,
    private val clock: Clock,
    val gateway: FakeStorageGateway = FakeStorageGateway(),
    private val deny: Boolean = false,
) : GrantMutator {

    /** Every mutation the port was asked to perform, in order. */
    val log: MutableList<String> = mutableListOf()

    override suspend fun persist(treeUri: String, flags: Int): PersistResult {
        log += "persist:$treeUri"
        if (deny) return PersistResult.GrantDenied
        val source = GrantUriMapping.sourceRef(
            treeUri = treeUri,
            writePermission = flags and GrantMutator.FLAG_WRITE != 0,
            grantedAt = clock.nowMillis(),
        )
        store.add(source)
        return PersistResult.Persisted(source)
    }

    override suspend fun revoke(id: SourceId) {
        log += "release:${id.value}"
        store.remove(id)
    }
}

/** A realistic SAF tree URI whose tree document id decodes to `Download`. */
const val TREE_DOWNLOAD: String =
    "content://com.android.externalstorage.documents/tree/primary%3ADownload"

/** A distinct tree URI per index, for cap tests. */
fun treeUri(index: Int): String =
    "content://com.android.externalstorage.documents/tree/primary%3AFolder$index"
