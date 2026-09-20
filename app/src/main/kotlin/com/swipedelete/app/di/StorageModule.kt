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
 * This type exists so tests can assert the graph's shape (and so the app has one
 * named place to resolve the whole stack from); production code should depend on
 * [StorageGateway], [Reconciler] or [QuarantineStore] directly, never on
 * [StorageGraph].
 */
class StorageGraph internal constructor(
    /** The outermost gateway: every mutating call enters here. */
    val gateway: StorageGateway,
    /** The innermost, platform-backed gateway. */
    val concrete: StorageGateway,
    val journal: JournalDao,
    /**
     * The one and only [QuarantineStore] in the graph. Its root is [quarantineRoot].
     * Do not resolve this outside the graph: the Quarantine layer owns the store,
     * and a second instance would mean a second quarantine root.
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
    fun providePersistedGrantStore(@ApplicationContext context: Context): PersistedGrantStore =
        ContentResolverPersistedGrantStore(context)

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
     * The concrete, platform-backed gateway. `quarantineStore` is deliberately
     * `null`: the Quarantine decorator owns the store (carry-forward 1), so a
     * second root cannot exist.
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
    fun provideStorageGraph(
        concrete: SafStorageGateway,
        journal: JournalDao,
        hasher: Hasher,
        clock: Clock,
        quarantineRoot: File,
        existenceProbe: ContentResolverExistenceProbe,
    ): StorageGraph = assembleStorageGraph(
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

    @Provides
    @Singleton
    fun provideStorageGateway(graph: StorageGraph): StorageGateway = graph.gateway

    @Provides
    @Singleton
    fun provideReconciler(graph: StorageGraph): Reconciler = graph.reconciler

    @Provides
    @Singleton
    fun provideQuarantineStore(graph: StorageGraph): QuarantineStore = graph.quarantineStore
}

private object WallClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}
