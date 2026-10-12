package com.swipedelete.feature.dashboard.quarantine

import com.swipedelete.core.common.UsableBytesSampler
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class QuarantineControllerTest {

    private class FakePort(var items: List<HeldItem>) : QuarantinePort {
        val emptyCalls = mutableListOf<Boolean>()
        var restoreAllCalls = 0
        var onEmpty: () -> Unit = {}

        override suspend fun held(): List<HeldItem> = items
        override suspend fun restoreAll(): Int { restoreAllCalls++; return items.size.also { items = emptyList() } }
        override suspend fun emptyAll(allowPermanent: Boolean): Int {
            emptyCalls += allowPermanent
            if (!allowPermanent) return 0
            return items.size.also { items = emptyList(); onEmpty() }
        }
    }

    private var usable = 1_000L
    private val sampler = UsableBytesSampler { usable }
    private val now = 0L

    private fun controller(port: FakePort) = QuarantineController(port, sampler) { now }

    private val twoFiles = listOf(HeldItem(100, 10_000), HeldItem(200, 20_000))

    @Test
    fun `refresh shows what is held`() = runTest {
        val c = controller(FakePort(twoFiles))

        c.refresh()

        assertIs<QuarantineCard.Held>(c.state.value.card)
    }

    @Test
    fun `cancelling the Empty flow deletes nothing`() = runTest {
        val port = FakePort(twoFiles)
        val c = controller(port).also { it.refresh() }

        c.beginEmpty(); c.cancelEmpty()
        c.beginEmpty(); c.proceedEmpty(); c.typeCount("2"); c.cancelEmpty()
        c.confirmEmpty()

        assertTrue(port.emptyCalls.isEmpty(), "port was asked to empty: ${port.emptyCalls}")
        assertEquals(2, port.items.size)
    }

    @Test
    fun `a wrong typed count deletes nothing`() = runTest {
        val port = FakePort(twoFiles)
        val c = controller(port).also { it.refresh() }

        c.beginEmpty(); c.proceedEmpty(); c.typeCount("3"); c.confirmEmpty()

        assertTrue(port.emptyCalls.isEmpty())
    }

    @Test
    fun `the typed count empties with permission and reports measured freed, not file sizes`() = runTest {
        val port = FakePort(twoFiles)
        port.onEmpty = { usable += 250 }
        val c = controller(port).also { it.refresh() }

        c.beginEmpty(); c.proceedEmpty(); c.typeCount("2"); c.confirmEmpty()

        assertEquals(listOf(true), port.emptyCalls)
        assertEquals(QuarantineCard.Empty, c.state.value.card)
        assertEquals("250.0 B freed (300.0 B expected)", c.state.value.message)
    }

    @Test
    fun `an Empty that frees nothing says so instead of claiming bytes`() = runTest {
        val port = FakePort(twoFiles)
        val c = controller(port).also { it.refresh() }

        c.beginEmpty(); c.proceedEmpty(); c.typeCount("2"); c.confirmEmpty()

        assertEquals("0 B freed (300.0 B expected)", c.state.value.message)
    }

    @Test
    fun `restore all returns the files and refreshes the card`() = runTest {
        val port = FakePort(twoFiles)
        val c = controller(port).also { it.refresh() }

        c.restoreAll()

        assertEquals(1, port.restoreAllCalls)
        assertEquals(QuarantineCard.Empty, c.state.value.card)
    }
}
