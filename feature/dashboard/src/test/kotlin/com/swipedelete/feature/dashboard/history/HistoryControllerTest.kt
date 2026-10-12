package com.swipedelete.feature.dashboard.history

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HistoryControllerTest {

    private fun row(id: String, batch: String, size: Long, status: HistoryRowStatus, at: Long = 0L) =
        HistoryRow(id, batch, "DCIM/Camera/$id.jpg", size, at, status, kind = "Quarantined")

    private class FakePort(var rows: List<HistoryRow>, var outcome: (String, Boolean) -> RestoreOutcome) : HistoryPort {
        val calls = mutableListOf<Pair<String, Boolean>>()
        override suspend fun rows() = rows
        override suspend fun restore(rowId: String, keepBoth: Boolean): RestoreOutcome {
            calls += rowId to keepBoth
            return outcome(rowId, keepBoth)
        }
    }

    @Test
    fun `rows are grouped into batches with date, count and bytes`() {
        val batches = historyBatches(
            listOf(
                row("a", "b1", 1024, HistoryRowStatus.Restorable, at = 5),
                row("b", "b1", 1024, HistoryRowStatus.Restorable, at = 5),
                row("c", "b2", 10, HistoryRowStatus.Restored, at = 9),
            ),
        )

        assertEquals(listOf("b2", "b1"), batches.map { it.batchId }, "newest batch first")
        val b1 = batches.last()
        assertEquals(2, b1.itemCount)
        assertEquals("2.0 KB", b1.bytesText)
        assertEquals(5L, b1.createdAt)
    }

    @Test
    fun `status text is honest about what can still be restored`() {
        assertEquals("Can be restored", HistoryRowStatus.Restorable.text)
        assertEquals("Restored", HistoryRowStatus.Restored.text)
        assertTrue("no longer" in HistoryRowStatus.NoLongerRestorable.text.lowercase())
    }

    @Test
    fun `only restorable rows offer Restore`() {
        assertTrue(HistoryRowStatus.Restorable.canRestore)
        assertTrue(
            listOf(HistoryRowStatus.Restored, HistoryRowStatus.NoLongerRestorable, HistoryRowStatus.Other)
                .none { it.canRestore },
        )
    }

    @Test
    fun `a successful restore refreshes the list`() = runTest {
        val port = FakePort(listOf(row("a", "b1", 1, HistoryRowStatus.Restorable))) { _, _ -> RestoreOutcome.Restored }
        val c = HistoryController(port).also { it.refresh() }
        port.rows = listOf(row("a", "b1", 1, HistoryRowStatus.Restored))

        c.restore("a")

        assertEquals(HistoryRowStatus.Restored, c.state.value.batches.single().rows.single().status)
        assertNull(c.state.value.conflict)
    }

    @Test
    fun `a conflict opens the choice screen and changes nothing until chosen`() = runTest {
        val port = FakePort(listOf(row("a", "b1", 1, HistoryRowStatus.Restorable))) { _, keep ->
            if (keep) RestoreOutcome.Restored else RestoreOutcome.Conflict
        }
        val c = HistoryController(port).also { it.refresh() }

        c.restore("a")

        assertEquals("a", c.state.value.conflict?.rowId)
        assertEquals(listOf("a" to false), port.calls)
    }

    @Test
    fun `skip closes the conflict without another restore`() = runTest {
        val port = FakePort(listOf(row("a", "b1", 1, HistoryRowStatus.Restorable))) { _, _ -> RestoreOutcome.Conflict }
        val c = HistoryController(port).also { it.refresh(); it.restore("a") }

        c.skipConflict()

        assertNull(c.state.value.conflict)
        assertEquals(1, port.calls.size)
    }

    @Test
    fun `keep both restores with keep-both and closes the conflict`() = runTest {
        val port = FakePort(listOf(row("a", "b1", 1, HistoryRowStatus.Restorable))) { _, keep ->
            if (keep) RestoreOutcome.Restored else RestoreOutcome.Conflict
        }
        val c = HistoryController(port).also { it.refresh(); it.restore("a") }

        c.keepBoth()

        assertEquals(listOf("a" to false, "a" to true), port.calls)
        assertNull(c.state.value.conflict)
    }

    @Test
    fun `a failed restore says so and keeps the file`() = runTest {
        val port = FakePort(listOf(row("a", "b1", 1, HistoryRowStatus.Restorable))) { _, _ ->
            RestoreOutcome.Failed("hash mismatch")
        }
        val c = HistoryController(port).also { it.refresh() }

        c.restore("a")

        assertTrue("hash mismatch" in c.state.value.message.orEmpty())
        assertEquals(HistoryRowStatus.Restorable, c.state.value.batches.single().rows.single().status)
    }
}
