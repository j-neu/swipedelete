package com.swipedelete.app.recovery

import com.swipedelete.core.common.Clock
import com.swipedelete.core.common.DispatchersProvider
import com.swipedelete.core.common.Sha256Hasher
import com.swipedelete.core.database.dao.JournalDao
import com.swipedelete.core.database.entity.JournalEntryEntity
import com.swipedelete.core.model.JournalState
import com.swipedelete.core.model.RecoveryReport
import com.swipedelete.core.storage.Reconciler
import com.swipedelete.core.storage.fake.FakeStorageGateway
import com.swipedelete.core.storage.quarantine.QuarantineStore
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * Finding 5: reconciliation is launched-not-awaited, so the readiness signal is
 * what makes "resolve every record before any UI can mutate storage" enforceable.
 *
 * The journal is parked inside `unresolved()` until released, proving [ready] is
 * `false` while reconciliation is in flight and only `true` once the report is
 * published; [RecoveryCoordinator.awaitReady] then returns that report.
 */
class RecoveryCoordinatorTest {

    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun cleanUp() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun `ready is false while reconciliation runs and true once the report is published`() = runTest {
        val journal = GatedJournalDao()
        val root = Files.createTempDirectory("sd-recovery-").toFile().also { tempDirs += it }
        val store = QuarantineStore(
            root = root,
            gateway = FakeStorageGateway(),
            hasher = Sha256Hasher(),
            clock = FixedClock,
        )
        val coordinator = RecoveryCoordinator(
            reconciler = Reconciler(journal, store) { false },
            dispatchers = TestDispatchers(UnconfinedTestDispatcher(testScheduler)),
        )

        assertFalse(coordinator.ready.value, "nothing may mutate storage before reconciliation starts")
        assertNull(coordinator.report.value)

        val job = coordinator.start()

        assertTrue(journal.started, "start() must schedule reconciliation")
        assertFalse(coordinator.ready.value, "ready must stay false while reconciliation is in flight")
        assertNull(coordinator.report.value, "no report may be published until reconciliation completes")

        journal.release()
        job.join()

        assertTrue(coordinator.ready.value, "ready must be true once reconciliation completes")
        val report = coordinator.awaitReady()
        assertTrue(report.resolved.isEmpty())
        assertTrue(report.unknown.isEmpty())
        assertEquals(
            RecoveryReport(resolved = emptyList(), unknown = emptyList()),
            report,
            "awaitReady must hand the destructive action the report it must act on",
        )
    }
}

/** Parks inside `unresolved()` until [release], so readiness can be observed mid-flight. */
private class GatedJournalDao : JournalDao {
    private val gate = CompletableDeferred<Unit>()

    @Volatile
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

private class TestDispatchers(private val dispatcher: CoroutineDispatcher) : DispatchersProvider {
    override val io: CoroutineDispatcher = dispatcher
    override val default: CoroutineDispatcher = dispatcher
    override val main: CoroutineDispatcher = dispatcher
}

private object FixedClock : Clock {
    override fun nowMillis(): Long = 1_700_000_000_000L
}
