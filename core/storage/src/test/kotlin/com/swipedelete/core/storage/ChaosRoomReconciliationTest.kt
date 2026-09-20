package com.swipedelete.core.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.swipedelete.core.database.SwipeDeleteDatabase
import com.swipedelete.core.model.JournalState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Finding 6: AC-1 durability under death over a **real Room database**.
 *
 * [ChaosReconciliationTest] runs the same 22-point sweep over an in-memory
 * `JournalDao`, which proves ordering but not the durability of the real
 * DAO/transaction path; the device AC-5 test abandons a coroutine at an I/O
 * boundary and never exercises a flush. This test reuses that harness — the same
 * [ChaosEnv], the same decorator stack and the same [assertChaosInvariants] — but
 * swaps [ChaosJournalDao] for [RoomBackedChaosJournal], so every `PENDING` insert
 * and terminal mark is a committed SQLite write.
 *
 * The four invariants are unchanged: no loss, no limbo, honest reconciliation,
 * nothing destroyed. The OS-level kill gap is still open and documented in
 * `docs/M0-ACCEPTANCE.md`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChaosRoomReconciliationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databases = mutableListOf<SwipeDeleteDatabase>()

    @After
    fun closeDatabases() {
        databases.forEach { runCatching { it.close() } }
        databases.clear()
    }

    @Test
    fun `the kill sweep over a real Room journal loses nothing and leaves no limbo`() = runTest {
        // Learn the primitive count from a real-Room run first, so the sweep range
        // is pinned against the durable path, not the in-memory one.
        val probeDb = newDatabase()
        val probe = ChaosEnv(journalFactory = { RoomBackedChaosJournal(probeDb.journalDao(), it) })
        val operations = try {
            probe.runQuarantineBatch(of = BATCH_SIZE)
            probe.operationCount()
        } finally {
            probe.dispose()
        }
        assertEquals(
            EXPECTED_OPERATIONS,
            operations,
            "the real-Room 5-item batch reached $operations primitives; the sweep range and " +
                "EXPECTED_OPERATIONS must be re-verified against the whole batch",
        )

        var sawPartialJournal = false
        var sawDuplicate = false
        var sawAllMovedButUnmarked = false

        for (stopAt in 0..operations) {
            val db = newDatabase()
            val env = ChaosEnv(
                stopAt = stopAt,
                journalFactory = { RoomBackedChaosJournal(db.journalDao(), it) },
                onDispose = { runCatching { db.close() } },
            )
            try {
                runCatching { env.runQuarantineBatch(of = BATCH_SIZE) }

                val expectedPrimitives = if (stopAt < operations) stopAt + 1 else operations
                assertEquals(
                    expectedPrimitives,
                    env.operationCount(),
                    "stopAt=$stopAt must stop the process at primitive $expectedPrimitives",
                )

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
                assertChaosInvariants(env, report, label = "room stopAt=$stopAt")
            } finally {
                env.dispose()
            }
        }

        assertTrue(sawPartialJournal, "the real-Room sweep never killed the batch mid-insert")
        assertTrue(sawDuplicate, "the real-Room sweep never killed the batch after a copy but before its original delete")
        assertTrue(
            sawAllMovedButUnmarked,
            "the real-Room sweep never killed the batch after every item moved but before any terminal mark",
        )
    }

    private fun newDatabase(): SwipeDeleteDatabase =
        Room.inMemoryDatabaseBuilder(context, SwipeDeleteDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .also { databases += it }
}
