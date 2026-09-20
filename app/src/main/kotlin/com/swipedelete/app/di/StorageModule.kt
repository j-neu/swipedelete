package com.swipedelete.app.di

import android.content.Context
import com.swipedelete.core.common.Clock
import com.swipedelete.core.common.Hasher
import com.swipedelete.core.common.Sha256Hasher
import com.swipedelete.core.database.dao.JournalDao
import com.swipedelete.core.model.MediaItem
import com.swipedelete.core.storage.JournalingStorageGateway
import com.swipedelete.core.storage.ProtectedRootsStorageGateway
import com.swipedelete.core.storage.QuarantineStorageGateway
import com.swipedelete.core.storage.Reconciler
import com.swipedelete.core.storage.StorageGateway
import com.swipedelete.core.storage.quarantine.QuarantineStore
import com.swipedelete.core.storage.saf.PersistedGrantStore
import com.swipedelete.core.storage.saf.SafStorageGateway
import com.swipedelete.feature.settings.grants.ContentResolverGrantStore
import com.swipedelete.feature.settings.grants.GrantMutator
import com.swipedelete.feature.settings.grants.SourceGrantRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/** Journal and quarantine retention: 30 days, the SPEC §6.1 window. */
const val DEFAULT_RETENTION_MILLIS: Long = 30L * 24 * 60 * 60 * 1000

/**
 * The assembled safety spine: one concrete gateway, one quarantine store, and the
 * production decorator stack over them.
 *
 * This type exists so [assembleStorageGraph] can return the whole assembly and so
 * tests can assert its shape. It is deliberately **not** a Hilt binding: see
 * [StorageGraphHandle] for why, and [StorageModule] for what is bound instead.
 */
class StorageGraph internal constructor(
    /** The outermost gateway: every mutating call enters here. */
    val gateway: StorageGateway,
    /** The innermost, platform-backed gateway. */
    val concrete: StorageGateway,
    val journal: JournalDao,
    /**
     * The one and only [QuarantineStore] in the graph. Its root is [quarantineRoot].
     *
     * Never bind this: the Quarantine layer owns the store, a second instance would
     * mean a second quarantine root, and an injectable store lets a consumer call
     * `store()`/`restore()`/`scan()` directly, bypassing the write-ahead journal
     * (AC-1). Reach quarantine through [gateway] or [Reconciler] instead.
     */
    val quarantineStore: QuarantineStore,
    val quarantineRoot: File,
    val reconciler: Reconciler,
)

/**
 * Builds the storage graph. This is the single construction path shared by Hilt
 * ([StorageModule]) and the tests, so the tested shape is the shipped shape.
 *
 * The decorator order is frozen: **ProtectedRoots → Journaling → Quarantine →
 * Concrete**, outermost first. A protected item is therefore refused by the
 * outermost decorator *before* the journaling layer can write anything (AC-7 is
 * evaluated before AC-1), which is why a refused item produces no journal row at
 * all rather than a compensated one.
 *
 * Three invariants are enforced here rather than left to callers:
 *
 * 1. **Exactly one [QuarantineStore].** It is created once, is the store the
 *    Quarantine decorator and the [Reconciler] both receive, and `concrete` is
 *    constructed with `quarantineStore = null` (see [StorageModule.provideConcreteGateway]).
 *    A concrete gateway that owned its own store would have a second root.
 * 2. **[ProtectedRootsStorageGateway] is outermost and gets a non-null
 *    [quarantineRoot].** Without it `ProtectedRootKind.INTERNAL` is never active
 *    and the quarantine directory is itself unprotected — the app could delete
 *    the very bytes it is holding for the user.
 * 3. **The store's inner gateway is `concrete`, not the decorator stack.**
 *    [QuarantineStore] reads and deletes the original through the gateway it is
 *    given; handing it the full stack would double-journal every quarantined item
 *    and re-check protected roots for items the outer decorator already
 *    authorised.
 */
fun assembleStorageGraph(
    concrete: StorageGateway,
    journal: JournalDao,
    quarantineRoot: File,
    hasher: Hasher,
    clock: Clock,
    exists: suspend (uri: String) -> Boolean,
    unlocked: () -> Set<String> = { emptySet() },
    isRoot: (MediaItem) -> Boolean = { false },
    now: () -> Long = System::currentTimeMillis,
    retentionMillis: Long = DEFAULT_RETENTION_MILLIS,
): StorageGraph {
    val quarantineStore = QuarantineStore(
        root = quarantineRoot,
        gateway = concrete,
        hasher = hasher,
        clock = clock,
        retentionMillis = retentionMillis,
    )
    val quarantine = QuarantineStorageGateway(delegate = concrete, store = quarantineStore)
    val journaling = JournalingStorageGateway(
        delegate = quarantine,
        journal = journal,
        clock = clock,
        retentionMillis = retentionMillis,
    )
    val gateway = ProtectedRootsStorageGateway(
        delegate = journaling,
        unlocked = unlocked,
        isRoot = isRoot,
        quarantineRoot = quarantineRoot.absolutePath,
        now = now,
    )
    return StorageGraph(
        gateway = gateway,
        concrete = concrete,
        journal = journal,
        quarantineStore = quarantineStore,
        quarantineRoot = quarantineRoot,
        reconciler = Reconciler(journal = journal, quarantine = quarantineStore, exists = exists),
    )
}

/**
 * Provider-local hand-off between [StorageModule]'s providers.
 *
 * Hilt can only share one built dependency between two providers by binding
 * something, and what must be shared here is a single [StorageGraph] so that the
 * mutating [StorageGateway] and the [Reconciler] use the *same* [QuarantineStore]
 * (one store, one quarantine root — carry-forward 1). Binding the graph itself
 * would make it injectable, and so would binding the store.
 *
 * This handle is therefore the exchange type, and its surface is deliberately
 * limited to the two entry points that are already legitimately injectable:
 *
 * - [gateway] — the outermost decorator, so every mutating call passes
 *   `ProtectedRoots → Journaling → Quarantine` first;
 * - [reconciler] — the startup pass, which owns its own per-row reasoning.
 *
 * It exposes neither [QuarantineStore] nor [StorageGraph]. Nothing can inject the
 * store and call `store()`/`restore()`/`scan()` behind the write-ahead journal
 * (AC-1), and nothing can reach the store through this type. Injecting the handle
 * yields no capability that [StorageGateway] and [Reconciler] do not already
 * provide individually.
 */
class StorageGraphHandle internal constructor(
    /** The outermost decorator stack; the only sanctioned mutating entry point. */
    val gateway: StorageGateway,
    val reconciler: Reconciler,
)

@Module
@InstallIn(SingletonComponent::class)
object StorageModule {

    @Provides
    @Singleton
    fun provideHasher(): Hasher = Sha256Hasher()

    @Provides
    @Singleton
    fun provideClock(): Clock = WallClock

    @Provides
    @Singleton
    fun provideGrantStore(@ApplicationContext context: Context): ContentResolverGrantStore =
        ContentResolverGrantStore(context)

    /**
     * The **one** persisted-grant implementation (M0-T16). Both ports are bound to
     * the same singleton, so the SAF gateway, the onboarding flow and the grants
     * screen read and write one grant set; there is no second grant store in the
     * app. The previous `:app`-local store was deleted when this was introduced.
     */
    @Provides
    @Singleton
    fun providePersistedGrantStore(store: ContentResolverGrantStore): PersistedGrantStore = store

    @Provides
    @Singleton
    fun provideGrantMutator(store: ContentResolverGrantStore): GrantMutator = store

    /**
     * The app's grant policy: the 20-grant cap, order and revoke (SPEC 4.5, V2).
     * It is Android-free, so it is the same class the host-JVM tests exercise.
     */
    @Provides
    @Singleton
    fun provideSourceGrantRepository(
        grants: PersistedGrantStore,
        mutator: GrantMutator,
        clock: Clock,
    ): SourceGrantRepository = SourceGrantRepository(grants = grants, mutator = mutator, clock = clock)

    /**
     * The quarantine root: `<app external files dir>/quarantine`, the same
     * directory [SafStorageGateway] would name. It is passed to
     * [ProtectedRootsStorageGateway] so the quarantine directory is protected by
     * `ProtectedRootKind.INTERNAL`.
     */
    @Provides
    @Singleton
    fun provideQuarantineRoot(@ApplicationContext context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, SafStorageGateway.QUARANTINE_DIR)

    /**
     * The concrete, platform-backed gateway: SAF, the tier-0/tier-1 default that
     * always exists.
     *
     * `quarantineStore` is deliberately `null`: the Quarantine decorator owns the
     * store (carry-forward 1), so a second root cannot exist.
     *
     * **Tier 2 (All Files Access) has no wired gateway yet.** Nothing here binds a
     * `RawStorageGateway`, so this SAF gateway is the only concrete implementation
     * in the graph and mutating calls made while All Files Access is granted still
     * go through SAF. Raw access is not bindable at graph-construction time because
     * it depends on a runtime permission *and* on per-volume root selection, both of
     * which can change while the process lives; a `@Singleton` built here would
     * freeze a stale permission state. `RawStorageGateway` (`:core:storage`, M0-T12)
     * is therefore constructed on demand by the first feature that needs it, which
     * is M1's `:feature:deck`/non-media work. Until then `TieredFeatureGate` may
     * report `Tier.TIER_2` as *available*, but no Tier-2 code path exists to use —
     * this note exists so that gap is discoverable from the module rather than only
     * from a report.
     */
    @Provides
    @Singleton
    fun provideConcreteGateway(
        @ApplicationContext context: Context,
        persisted: PersistedGrantStore,
    ): SafStorageGateway = SafStorageGateway(
        context = context,
        persisted = persisted,
        quarantineStore = null,
    )

    @Provides
    @Singleton
    fun provideExistenceProbe(@ApplicationContext context: Context): ContentResolverExistenceProbe =
        ContentResolverExistenceProbe(context)

    @Provides
    @Singleton
    fun provideStorageGraphHandle(
        concrete: SafStorageGateway,
        journal: JournalDao,
        hasher: Hasher,
        clock: Clock,
        quarantineRoot: File,
        existenceProbe: ContentResolverExistenceProbe,
    ): StorageGraphHandle {
        val graph = assembleStorageGraph(
            concrete = concrete,
            journal = journal,
            quarantineRoot = quarantineRoot,
            hasher = hasher,
            clock = clock,
            exists = existenceProbe::exists,
            // Revocation and unlocked protected roots are Task 25/M3 concerns: until
            // then nothing is unlocked and no item is a source root.
            unlocked = { emptySet() },
        )
        return StorageGraphHandle(gateway = graph.gateway, reconciler = graph.reconciler)
    }

    @Provides
    @Singleton
    fun provideStorageGateway(handle: StorageGraphHandle): StorageGateway = handle.gateway

    @Provides
    @Singleton
    fun provideReconciler(handle: StorageGraphHandle): Reconciler = handle.reconciler
}

private object WallClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}
