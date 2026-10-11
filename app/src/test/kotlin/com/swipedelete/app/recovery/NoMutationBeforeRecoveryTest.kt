package com.swipedelete.app.recovery

import com.swipedelete.core.common.Clock
import com.swipedelete.core.common.DispatchersProvider
import com.swipedelete.core.common.Sha256Hasher
import com.swipedelete.core.database.dao.DeckSessionDao
import com.swipedelete.core.database.dao.JournalDao
import com.swipedelete.core.database.entity.DeckSessionEntity
import com.swipedelete.core.database.entity.JournalEntryEntity
import com.swipedelete.core.model.BatchId
import com.swipedelete.core.model.DeckStatus
import com.swipedelete.core.model.ItemId
import com.swipedelete.core.model.JournalEntry
import com.swipedelete.core.model.JournalState
import com.swipedelete.core.model.MediaItem
import com.swipedelete.core.model.SessionId
import com.swipedelete.core.model.SourceId
import com.swipedelete.core.model.SourceKind
import com.swipedelete.core.model.SourceRef
import com.swipedelete.core.storage.PageToken
import com.swipedelete.core.storage.Reconciler
import com.swipedelete.core.storage.fake.FakeStorageGateway
import com.swipedelete.core.storage.quarantine.QuarantineStore
import com.swipedelete.core.storage.scan.ScanPage
import com.swipedelete.core.storage.scan.SourceScanPort
import com.swipedelete.feature.deck.domain.DeckBuilder
import com.swipedelete.feature.deck.domain.DeckQuery
import com.swipedelete.feature.deck.domain.Gesture
import com.swipedelete.feature.deck.session.MutationGate
import com.swipedelete.feature.deck.session.SessionController
import com.swipedelete.feature.deck.session.QuarantineOutcome
import com.swipedelete.feature.deck.session.SessionMutations
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * D6 / M1-T13: recovery readiness is a hard precondition. Every destructive
 * session entry point is reached only after `RecoveryCoordinator.awaitReady()`
 * returns, so a deck cannot mutate storage against an unresolved journal.
 *
 * Reconciliation is parked inside `unresolved()`; the destructive call is
 * launched and must not reach the mutation port while readiness is false. Once
 * the journal is released, `awaitReady()` returns and the same call runs.
 */
class NoMutationBeforeRecoveryTest {

    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun cleanUp() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun `restore-everything cannot run before recovery is ready`() = runTest {
        val (coordinator, journal) = gatedCoordinator()
        val mutations = RecordingSessionMutations()
        val controller = sessionController(coordinator, mutations, stagedItems = 0)

        controller.start(DeckQuery.Folder(SOURCE.id))

        val job = launch { controller.restoreEverything() }
        coordinator.start()
        runCurrent()

        assertTrue(
            mutations.restoreCalls == 0,
            "a destructive restore must not run while recovery is not ready",
        )

        journal.release()
        job.join()

        assertEquals(1, mutations.restoreCalls, "the restore runs once recovery is ready")
    }

    @Test
    fun `apply-staged cannot quarantine before recovery is ready`() = runTest {
        val (coordinator, journal) = gatedCoordinator()
        val mutations = RecordingSessionMutations()
        val controller = sessionController(coordinator, mutations, stagedItems = 1)
        controller.start(DeckQuery.Folder(SOURCE.id))
        controller.decide(Gesture.DELETE)

        val job = launch { controller.applyStaged() }
        coordinator.start()
        runCurrent()

        assertTrue(
            mutations.quarantined.isEmpty(),
            "no bytes may move into quarantine while recovery is not ready",
        )

        journal.release()
        job.join()

        assertEquals(1, mutations.quarantined.size, "the quarantine runs once recovery is ready")
    }

    // --- Harness --------------------------------------------------------------

    private fun TestScope.gatedCoordinator(): Pair<RecoveryCoordinator, GatedRecoveryJournalDao> {
        val journal = GatedRecoveryJournalDao()
        val root = Files.createTempDirectory("sd-nomutation-").toFile().also { tempDirs += it }
        val store = QuarantineStore(
            root = root,
            gateway = FakeStorageGateway(),
            hasher = Sha256Hasher(),
            clock = NoMutationClock,
        )
        val coordinator = RecoveryCoordinator(
            reconciler = Reconciler(journal, store) { false },
            dispatchers = RecoveryTestDispatchers(UnconfinedTestDispatcher(testScheduler)),
        )
        return coordinator to journal
    }

    private fun sessionController(
        coordinator: RecoveryCoordinator,
        mutations: RecordingSessionMutations,
        stagedItems: Int,
    ): SessionController {
        val gateway = FakeStorageGateway()
        gateway.addSource(SOURCE)
        val items = (1..stagedItems).map { index ->
            MediaItem(
                id = ItemId("i$index"),
                uri = "content://tree/src/i$index.jpg",
                path = "content://tree/src/i$index.jpg",
                relativePath = null,
                mediaStoreId = null,
                mime = "image/jpeg",
                sizeBytes = 1024L,
                width = null,
                height = null,
                durationMs = null,
                dateAdded = 0L,
                dateModified = 0L,
                bucket = null,
                sourceId = SOURCE.id,
            )
        }
        return SessionController(
            sessions = InMemoryDeckSessionDao(),
            builder = DeckBuilder({ CannedScanPort(items) }, gateway, NoMutationClock),
            clock = NoMutationClock,
            gate = MutationGate { coordinator.awaitReady() },
            mutations = mutations,
            measuredFreed = { 0L },
            reclaimable = { 0L },
            sessionIds = { SESSION },
            batches = { BatchId("batch-1") },
        )
    }

    private companion object {
        val SOURCE = SourceRef(
            id = SourceId("src"),
            treeUri = "content://tree/src",
            displayName = "src",
            access = SourceRef.Access.RW,
            kind = SourceKind.SAF,
            itemCount = null,
            totalBytes = null,
            grantedAt = 0L,
        )
        val SESSION = SessionId("session-1")
    }
}

/** Parks inside `unresolved()` until [release], so readiness can be observed mid-flight. */
private class GatedRecoveryJournalDao : JournalDao {
    private val gate = CompletableDeferred<Unit>()

    var started: Boolean = false

    fun release() = gate.complete(Unit)

    override suspend fun unresolved(): List<JournalEntryEntity> {
        started = true
        gate.await()
        return emptyList()
    }

    override suspend fun insertPending(entry: JournalEntryEntity): Long = 0L
    override suspend fun markOutcome(id: String, state: JournalState, error: String?): Int = 0
    override suspend fun pending(): List<JournalEntryEntity> = emptyList()
    override suspend fun page(limit: Int, offset: Int): List<JournalEntryEntity> = emptyList()
    override suspend fun byBatch(batchId: String): List<JournalEntryEntity> = emptyList()
}

private class RecordingSessionMutations : SessionMutations {
    val quarantined = mutableListOf<List<MediaItem>>()
    var restoreCalls = 0

    override suspend fun quarantine(items: List<MediaItem>, batch: BatchId): QuarantineOutcome {
        quarantined += items
        return QuarantineOutcome(moved = items.mapTo(linkedSetOf()) { it.id }, failures = emptyList())
    }

    override suspend fun restore(entries: List<JournalEntry>): Set<ItemId> {
        restoreCalls++
        return emptySet()
    }
}

private class InMemoryDeckSessionDao : DeckSessionDao {
    private val rows = LinkedHashMap<String, DeckSessionEntity>()

    override suspend fun upsert(session: DeckSessionEntity) {
        rows[session.id] = session
    }

    override suspend fun byId(id: String): DeckSessionEntity? = rows[id]
    override suspend fun active(): List<DeckSessionEntity> = emptyList()
    override suspend fun recent(limit: Int): List<DeckSessionEntity> = emptyList()
    override suspend fun delete(id: String) {
        rows.remove(id)
    }

    override suspend fun persistProgress(id: String, cursor: Int, stateData: String?): Int {
        val row = rows[id] ?: return 0
        rows[id] = row.copy(cursor = cursor, stateData = stateData)
        return 1
    }

    override suspend fun markStatus(id: String, status: DeckStatus): Int {
        val row = rows[id] ?: return 0
        rows[id] = row.copy(status = status)
        return 1
    }

    override suspend fun latestActive(): DeckSessionEntity? = null
}

private class CannedScanPort(private val items: List<MediaItem>) : SourceScanPort {
    override fun scan(source: SourceRef, page: PageToken?): Flow<ScanPage> = flow {
        emit(ScanPage(items = items, next = null, truncated = false, scanned = items.size))
    }
}

private class RecoveryTestDispatchers(private val dispatcher: CoroutineDispatcher) : DispatchersProvider {
    override val io: CoroutineDispatcher = dispatcher
    override val default: CoroutineDispatcher = dispatcher
    override val main: CoroutineDispatcher = dispatcher
}

private object NoMutationClock : Clock {
    override fun nowMillis(): Long = 1_700_000_000_000L
}
