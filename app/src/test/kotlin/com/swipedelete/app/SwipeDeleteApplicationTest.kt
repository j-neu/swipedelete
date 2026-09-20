package com.swipedelete.app

import androidx.test.core.app.ApplicationProvider
import com.swipedelete.app.recovery.RecoveryCoordinator
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AC-5 at startup: `Application.onCreate` must publish a [RecoveryReport] on the
 * injected IO dispatcher without waiting for reconciliation, so a slow disk never
 * delays the first frame.
 *
 * The test drives the real [SwipeDeleteApplication.onCreate] through Robolectric
 * with a test dispatcher and a reconciler parked mid-flight, installed by
 * [SwipeDeleteTestApplication]'s override of the production seam.
 *
 * The harness is installed in `@BeforeClass` because Robolectric creates the
 * application as part of its per-method environment, *before* `@Before` runs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = SwipeDeleteTestApplication::class)
class SwipeDeleteApplicationTest {

    private val journal: GatedJournalDao get() = Harness.journal

    @Test
    fun `application onCreate publishes a recovery report without blocking`() {
        val application = ApplicationProvider.getApplicationContext<SwipeDeleteApplication>()
        assertTrue(
            application is SwipeDeleteTestApplication,
            "the application under test must be the sewn test application, was ${application.javaClass.name}",
        )

        assertTrue(journal.started, "onCreate must launch reconciliation on the IO dispatcher")
        assertFalse(journal.finished, "onCreate must return while reconciliation is still in flight")
        assertNull(
            application.recoveryReport.value,
            "onCreate must not block until the report is ready",
        )

        journal.release()
        Harness.scheduler.advanceUntilIdle()

        assertEquals(
            RecoveryReport(resolved = emptyList(), unknown = emptyList()),
            application.recoveryReport.value,
        )
        assertTrue(journal.finished, "the launched reconciliation must run to completion")
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun installHarness() = Harness.install()

        @JvmStatic
        @AfterClass
        fun removeHarness() = Harness.clear()
    }
}

/**
 * Production override seam: returns whatever coordinator the test installed
 * before Robolectric created the application. Without an installed coordinator it
 * falls back to the Hilt-injected one.
 */
class SwipeDeleteTestApplication : SwipeDeleteApplication() {
    override fun recoveryCoordinator(): RecoveryCoordinator =
        TestApplicationGraph.coordinator ?: super.recoveryCoordinator()
}

internal object TestApplicationGraph {
    @Volatile
    var coordinator: RecoveryCoordinator? = null

    fun clear() {
        coordinator = null
    }
}

/** Installed in `@BeforeClass`, so it exists before Robolectric builds the application. */
private object Harness {
    val scheduler = TestCoroutineScheduler()
    lateinit var journal: GatedJournalDao
    private var quarantineRoot: File? = null

    fun install() {
        journal = GatedJournalDao()
        val root = Files.createTempDirectory("sd-app-quarantine-").toFile()
        quarantineRoot = root
        val store = QuarantineStore(
            root = root,
            gateway = FakeStorageGateway(),
            hasher = Sha256Hasher(),
            clock = FixedClock(),
        )
        val reconciler = Reconciler(journal, store) { false }
        TestApplicationGraph.coordinator = RecoveryCoordinator(
            reconciler = reconciler,
            dispatchers = TestDispatchers(UnconfinedTestDispatcher(scheduler)),
        )
    }

    fun clear() {
        TestApplicationGraph.clear()
        quarantineRoot?.deleteRecursively()
    }
}

/** Parks inside `unresolved()` until [release], proving onCreate does not wait. */
private class GatedJournalDao : JournalDao {
    private val gate = CompletableDeferred<Unit>()

    @Volatile
    var started: Boolean = false

    @Volatile
    var finished: Boolean = false

    fun release() {
        gate.complete(Unit)
    }

    override suspend fun unresolved(): List<JournalEntryEntity> {
        started = true
        gate.await()
        finished = true
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

private class FixedClock : Clock {
    override fun nowMillis(): Long = 1_700_000_000_000L
}
