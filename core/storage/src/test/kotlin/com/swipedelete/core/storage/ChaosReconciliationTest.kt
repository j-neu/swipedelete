package com.swipedelete.core.storage

import com.swipedelete.core.common.Clock
import com.swipedelete.core.common.Sha256Hasher
import com.swipedelete.core.database.dao.JournalDao
import com.swipedelete.core.database.entity.JournalEntryEntity
import com.swipedelete.core.model.BatchId
import com.swipedelete.core.model.ItemId
import com.swipedelete.core.model.JournalState
import com.swipedelete.core.model.MediaItem
import com.swipedelete.core.model.OpKind
import com.swipedelete.core.model.RecoveryReport
import com.swipedelete.core.model.SourceId
import com.swipedelete.core.model.SourceKind
import com.swipedelete.core.model.SourceRef
import com.swipedelete.core.storage.fake.FakeStorageGateway
import com.swipedelete.core.storage.fake.FaultInjector
import com.swipedelete.core.storage.fake.ProcessDeathException
import com.swipedelete.core.storage.quarantine.QuarantineStore
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AC-5, executable form: **process death at any point leaves no item in an
 * unknown state; reconciliation resolves every record.**
 *
 * The harness builds the real production decorator stack
 * `ProtectedRoots → Journaling → Quarantine → FakeStorageGateway` and abandons
 * the in-flight coroutine at operation `stopAt` by throwing a
 * [ProcessDeathException]. That exception is a `CancellationException`, so every
 * layer rethrows it untouched — no journal compensation and no finalisation, the
 * way an abrupt process exit leaves the durable state. Reconciliation then runs
 * against the *same* journal, gateway and quarantine state.
 *
 * Because a real crash may legitimately leave rows in the Recovery Report
 * (spec §6.2: "no item is ever in an unknown state without the user being told"),
 * the fuzzed invariant is not "the report is empty" but:
 *
 * - **(A) no loss** — every item's bytes are at its source and/or in quarantine,
 * - **(B) no limbo** — every journal row is terminal (`DONE`/`ABORTED`) or reported,
 * - **(C) honest** — an item in quarantine whose row is not `DONE` is reported,
 * - **(D) no destruction into the unknown** — all items are still discoverable.
 */
class ChaosReconciliationTest {

    @Test
    fun `no item is lost or left in limbo when killed at any of N points in a batch`() = runTest {
        // First run the batch untriggered to learn exactly how many primitives it
        // reaches; the sweep must include every one of them plus completion.
        val probe = ChaosEnv(stopAt = null)
        val operations = try {
            probe.runQuarantineBatch(of = BATCH_SIZE)
            probe.operationCount()
        } finally {
            probe.dispose()
        }
        assertEquals(
            EXPECTED_OPERATIONS,
            operations,
            "the 5-item batch reached $operations primitives; the sweep range and " +
                "EXPECTED_OPERATIONS must be re-verified against the whole batch",
        )

        // Coverage guards: the sweep must actually reach the interesting windows,
        // not merely re-assert the invariant over identical completed states.
        var sawPartialJournal = false
        var sawDuplicate = false
        var sawAllMovedButUnmarked = false

        for (stopAt in 0..operations) {
            val env = ChaosEnv(stopAt = stopAt)
            try {
                runCatching { env.runQuarantineBatch(of = BATCH_SIZE) }

                // The death must be real and exact: it abandons the coroutine at
                // the (stopAt+1)-th primitive, so no primitive after it runs. At
                // the final point the batch completes without dying.
                val expectedPrimitives = if (stopAt < operations) stopAt + 1 else operations
                assertEquals(
                    expectedPrimitives,
                    env.operationCount(),
                    "stopAt=$stopAt must stop the process at primitive $expectedPrimitives",
                )

                // Observe the pre-reconciliation shape for the coverage guards.
                val rowsBefore = env.journal.rows()
                val quarantinedBefore = env.store.scan().map { it.sidecar.itemId }.toSet()
                if (rowsBefore.size < BATCH_SIZE) sawPartialJournal = true
                if (env.items.any { env.fake.fileExists(it.path!!) && it.id.value in quarantinedBefore }) {
                    sawDuplicate = true
                }
                if (rowsBefore.size == BATCH_SIZE &&
                    rowsBefore.all { it.state == JournalState.PENDING } &&
                    quarantinedBefore.size == BATCH_SIZE
                ) {
                    sawAllMovedButUnmarked = true
                }

                val report = env.reconciler.reconcile()
                assertChaosInvariants(env, report, label = "stopAt=$stopAt")
            } finally {
                env.dispose()
            }
        }

        assertTrue(sawPartialJournal, "the sweep never killed the batch mid-insert")
        assertTrue(sawDuplicate, "the sweep never killed the batch after a copy but before its original delete")
        assertTrue(
            sawAllMovedButUnmarked,
            "the sweep never killed the batch after every item moved but before any terminal mark",
        )
    }

    @Test
    fun `an injected mid-batch failure leaves no loss and reconciles to a truthful state`() = runTest {
        val scenarios: List<Pair<String, (FaultInjector) -> Unit>> = listOf(
            // A delegate failure after item 1 moved: item 2's copy is made but its
            // original is not deleted, and the delegate reports it FAILED.
            "delete #2" to { it.failOnOperation(OpKind.DELETE_PERMANENT, 2) },
            // A delegate failure before any item moved: openStream #1 fails.
            "openStream #1" to { it.failOnCall(7) },
            // A failure during the finalisation commit: the first DONE mark throws,
            // leaving every row PENDING although every item moved.
            "commit DONE" to { it.throwOnCommit(JournalState.DONE) },
        )

        scenarios.forEach { (label, arm) ->
            val env = ChaosEnv(stopAt = null)
            arm(env.injector)
            try {
                runCatching { env.runQuarantineBatch(of = BATCH_SIZE) }
                val report = env.reconciler.reconcile()
                assertChaosInvariants(env, report, label = label)

                // Truthful classification: a moved item must not be left FAILED,
                // and an unmoved item must not be reported as moved (DONE).
                env.items.forEach { item ->
                    val moved = env.store.scan().any { it.sidecar.itemId == item.id.value }
                    val row = env.journal.rowFor(item)
                    if (row == null) return@forEach
                    if (moved) {
                        assertEquals(
                            JournalState.DONE,
                            row.state,
                            "[$label] item ${item.id.value} is in quarantine but its row is ${row.state}",
                        )
                    } else if (env.fake.fileExists(item.path!!)) {
                        assertEquals(
                            JournalState.ABORTED,
                            row.state,
                            "[$label] item ${item.id.value} never moved but its row is ${row.state}",
                        )
                    }
                }
            } finally {
                env.dispose()
            }
        }
    }

    @Test
    fun `fault injection can be armed and disarmed deterministically`() = runTest {
        // Armed: the injected fault is observed, so the harness is genuinely
        // injecting at the requested primitive.
        val armed = ChaosEnv(stopAt = null)
        val armedResult = try {
            armed.injector.failOnCall(7)
            runCatching { armed.runQuarantineBatch(of = BATCH_SIZE) }
            armed.journal.rows()
        } finally {
            armed.dispose()
        }
        assertTrue(
            armedResult.any { it.state == JournalState.FAILED },
            "an armed injector must fail the batch (openStream #1): ${armedResult.map { it.state }}",
        )

        // Disarmed: the same arms, cleared by reset(), inject nothing.
        val reset = ChaosEnv(stopAt = null)
        try {
            reset.injector.failOnCall(7)
            reset.injector.failOnOperation(OpKind.DELETE_PERMANENT, 2)
            reset.injector.throwOnCommit(JournalState.DONE)
            reset.injector.reset()

            val outcome = runCatching { reset.runQuarantineBatch(of = BATCH_SIZE) }
            assertTrue(outcome.isSuccess, "a reset injector must not inject: ${outcome.exceptionOrNull()}")
            assertTrue(
                reset.journal.rows().all { it.state == JournalState.DONE },
                "every row must be DONE after a disarmed run: ${reset.journal.rows().map { it.state }}",
            )

            val report = reset.reconciler.reconcile()
            assertTrue(report.unknown.isEmpty(), "a disarmed run must reconcile cleanly: ${report.unknown}")
            assertChaosInvariants(reset, report, label = "disarmed")
        } finally {
            reset.dispose()
        }
    }
}

// --- The re-frozen AC-5 invariant -------------------------------------------

internal suspend fun assertChaosInvariants(env: ChaosEnv, report: RecoveryReport, label: String) {
    val rows = env.journal.rows()
    val unknown = report.unknown.map { it.id }.toSet()
    val quarantined = env.store.scan()

    // (A) No loss: bytes are at the original source and/or in the quarantine store.
    env.items.forEach { item ->
        val atSource = env.fake.fileExists(item.path!!)
        val inQuarantine = quarantined.any { it.sidecar.itemId == item.id.value }
        assertTrue(
            atSource || inQuarantine,
            "$label (A) item ${item.id.value} lost: not at ${item.path} and not in quarantine",
        )
    }

    // (B) No limbo: every row is terminal or reported in the Recovery Report.
    rows.forEach { row ->
        val terminal = row.state == JournalState.DONE || row.state == JournalState.ABORTED
        assertTrue(
            terminal || row.id in unknown,
            "$label (B) row ${row.id} (item=${row.itemId}) left ${row.state} and was not reported " +
                "(unknown=$unknown)",
        )
    }

    // (C) Honesty: an item sitting in quarantine must be DONE or reported.
    quarantined.forEach { entry ->
        val row = rows.firstOrNull {
            it.batchId == entry.sidecar.batchId && it.fromUri == entry.sidecar.originalUri
        }
        if (row != null && row.state != JournalState.DONE) {
            assertTrue(
                row.id in unknown,
                "$label (C) item ${entry.sidecar.itemId} is in quarantine but row ${row.id} is " +
                    "${row.state} and was not reported",
            )
        }
    }

    // (D) No destruction into the unknown: every item is still discoverable.
    val discoverable = buildSet {
        env.items.forEach { if (env.fake.fileExists(it.path!!)) add(it.id.value) }
        quarantined.forEach { add(it.sidecar.itemId) }
    }
    assertTrue(
        discoverable.size >= env.items.size,
        "$label (D) only ${discoverable.size} of ${env.items.size} items discoverable across " +
            "source + quarantine",
    )
    env.items.forEach { item ->
        assertTrue(
            item.id.value in discoverable,
            "$label (D) item ${item.id.value} is discoverable in neither source nor quarantine",
        )
    }
}

// --- Harness -----------------------------------------------------------------

/**
 * One isolated run of a 5-item quarantine batch through the real production
 * stack. `stopAt` arms a process death at the start of the (stopAt+1)-th
 * primitive, i.e. after exactly `stopAt` of them have happened.
 */
internal class ChaosEnv(
    stopAt: Int? = null,
    journalFactory: (FaultInjector) -> ChaosJournal = { ChaosJournalDao(it) },
    private val onDispose: () -> Unit = {},
) {
    val base: File = Files.createTempDirectory("sd-chaos-base-").toFile()
    val root: File = Files.createTempDirectory("sd-chaos-quarantine-").toFile()
    val clock = ChaosClock(NOW)
    val fake = FakeStorageGateway()
    val injector: FaultInjector = fake.faultInjector
    val store = QuarantineStore(root, fake, Sha256Hasher(), clock)
    val journal: ChaosJournal = journalFactory(injector)
    val gateway: StorageGateway
    val reconciler: Reconciler
    val items: List<MediaItem>

    /**
     * Set the moment the batch call returns or dies. The death trigger is only
     * armed while the batch itself is running, so reconciliation — which reuses
     * the same journal — never trips it.
     */
    @Volatile
    private var batchComplete = false

    init {
        val source = SourceRef(
            id = SourceId("src-chaos"),
            treeUri = base.absolutePath,
            displayName = "Chaos",
            access = SourceRef.Access.RW,
            kind = SourceKind.RAW,
            itemCount = null,
            totalBytes = null,
            grantedAt = 0L,
        )
        fake.addSource(source)
        items = (1..BATCH_SIZE).map { index ->
            val relative = "Memes/$index.gif"
            val path = File(base, relative).absolutePath
            val content = "chaos-item-$index".repeat(4).toByteArray()
            val item = MediaItem(
                id = ItemId("item-$index"),
                uri = path,
                path = path,
                relativePath = relative,
                mediaStoreId = null,
                mime = FakeStorageGateway.mimeFor(relative),
                sizeBytes = content.size.toLong(),
                width = null,
                height = null,
                durationMs = null,
                dateAdded = 0L,
                dateModified = NOW,
                bucket = null,
                sourceId = source.id,
                hash = null,
            )
            fake.register(item, content)
            item
        }

        val quarantine = QuarantineStorageGateway(fake, store)
        val journaling = JournalingStorageGateway(quarantine, journal, clock)
        gateway = ProtectedRootsStorageGateway(
            delegate = journaling,
            unlocked = { emptySet() },
            quarantineRoot = root.absolutePath,
            now = { NOW },
        )
        reconciler = Reconciler(journal, store) { fake.fileExists(it) || File(it).isFile }

        stopAt?.let { point ->
            injector.beforeOperation = { index, _ ->
                if (!batchComplete && index > point) {
                    throw ProcessDeathException("process death at stopAt=$point (operation $index)")
                }
            }
        }
    }

    suspend fun runQuarantineBatch(of: Int): QuarantineResult {
        require(of == items.size) { "the batch holds ${items.size} items, asked for $of" }
        return try {
            gateway.quarantine(items, BatchId(BATCH_ID))
        } finally {
            batchComplete = true
        }
    }

    fun operationCount(): Int = injector.operationCount()

    fun dispose() {
        base.deleteRecursively()
        root.deleteRecursively()
        onDispose()
    }
}

/**
 * The journal the process dies with. It is deliberately the *only* stateful
 * survivor besides the fake gateway and the real quarantine directory, and it
 * reports each commit to the [FaultInjector] so journal writes are counted and
 * armable alongside the gateway's operations.
 */
internal interface ChaosJournal : JournalDao {
    /** Every durable row, for the invariant checks. */
    suspend fun rows(): List<JournalEntryEntity>

    /** The row the journal wrote for [item], matched the way the journal writes it. */
    suspend fun rowFor(item: MediaItem): JournalEntryEntity?
}

private class ChaosJournalDao(private val injector: FaultInjector) : ChaosJournal {
    private val rows = mutableListOf<JournalEntryEntity>()

    override suspend fun rows(): List<JournalEntryEntity> = rows.toList()

    override suspend fun rowFor(item: MediaItem): JournalEntryEntity? =
        rows.firstOrNull { it.itemId == item.id.value }

    override suspend fun insertPending(entry: JournalEntryEntity): Long {
        injector.beforeCommit(entry.opKind, JournalState.PENDING)
        rows += entry
        return rows.size.toLong()
    }

    override suspend fun markOutcome(id: String, state: JournalState, error: String?): Int {
        val index = rows.indexOfFirst { it.id == id }
        if (index < 0) return 0
        injector.beforeCommit(rows[index].opKind, state)
        rows[index] = rows[index].copy(state = state, error = error)
        return 1
    }

    override suspend fun pending(): List<JournalEntryEntity> =
        rows.filter { it.state == JournalState.PENDING }

    override suspend fun unresolved(): List<JournalEntryEntity> =
        rows.filter { it.state == JournalState.PENDING || it.state == JournalState.FAILED }

    override suspend fun page(limit: Int, offset: Int): List<JournalEntryEntity> =
        rows.drop(offset).take(limit)

    override suspend fun byBatch(batchId: String): List<JournalEntryEntity> =
        rows.filter { it.batchId == batchId }
}

/**
 * The same [ChaosJournal] contract, backed by a **real Room database** (finding
 * 6). Every commit is delegated to the real DAO/transaction path and only *then*
 * counted, so the 22-point kill sweep exercises the same ordering over durable
 * SQLite writes. [opKindById] is the only state kept here — Room owns the rows.
 */
internal class RoomBackedChaosJournal(
    private val delegate: JournalDao,
    private val injector: FaultInjector,
) : ChaosJournal {
    private val opKindById = mutableMapOf<String, OpKind>()

    override suspend fun insertPending(entry: JournalEntryEntity): Long {
        injector.beforeCommit(entry.opKind, JournalState.PENDING)
        opKindById[entry.id] = entry.opKind
        return delegate.insertPending(entry)
    }

    override suspend fun markOutcome(id: String, state: JournalState, error: String?): Int {
        injector.beforeCommit(opKindById[id] ?: OpKind.QUARANTINE, state)
        return delegate.markOutcome(id, state, error)
    }

    override suspend fun pending(): List<JournalEntryEntity> = delegate.pending()

    override suspend fun unresolved(): List<JournalEntryEntity> = delegate.unresolved()

    override suspend fun page(limit: Int, offset: Int): List<JournalEntryEntity> =
        delegate.page(limit, offset)

    override suspend fun byBatch(batchId: String): List<JournalEntryEntity> =
        delegate.byBatch(batchId)

    override suspend fun rows(): List<JournalEntryEntity> = delegate.page(limit = ROW_LIMIT, offset = 0)

    override suspend fun rowFor(item: MediaItem): JournalEntryEntity? =
        rows().firstOrNull { it.itemId == item.id.value }

    private companion object {
        /** The sweep holds at most [BATCH_SIZE] rows; the page query is comfortably larger. */
        const val ROW_LIMIT = 1_000
    }
}

/** A clock pinned to [now]; top-level classes share this package, so the name is unique. */
internal class ChaosClock(private val now: Long) : Clock {
    override fun nowMillis(): Long = now
}

internal const val BATCH_SIZE = 5
internal const val BATCH_ID = "chaos-b1"

/**
 * Primitive operations one 5-item quarantine batch reaches through the real
 * stack: 5 pending inserts → capacity pre-flight → 5 × (openStream, delete) →
 * 5 terminal marks. Pinned so a change that shortens the sweep is caught.
 */
internal const val EXPECTED_OPERATIONS = 21

private const val NOW = 1_700_000_000_000L
