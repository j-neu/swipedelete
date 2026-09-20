package com.swipedelete.core.storage

import com.swipedelete.core.model.BatchId
import com.swipedelete.core.model.JournalEntry
import com.swipedelete.core.model.MediaItem
import com.swipedelete.core.storage.fake.FakeStorageGateway
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class FakeStorageGatewayContractTest : StorageGatewayContractTest() {

    private var fake: FakeStorageGateway? = null

    override fun newGateway(items: List<MediaItem>): StorageGateway =
        FakeStorageGateway(items).also { fake = it }

    private val gateway: FakeStorageGateway
        get() = requireNotNull(fake) { "newGateway() has not been called" }

    override fun destinationWith(existingName: String): Destination {
        val source = destinationSource()
        gateway.addSource(source)
        gateway.createFileAt(pathOf(source, existingName))
        return Destination(source)
    }

    override fun newFileAt(path: String) = gateway.createFileAt(path)

    override fun fileExists(path: String): Boolean = gateway.fileExists(path)

    override fun quarantinePathFor(entry: JournalEntry): String =
        requireNotNull(entry.toUri) { "quarantine entry has no toUri" }

    override suspend fun armCancellation(gateway: StorageGateway) {
        (gateway as FakeStorageGateway).beforeMove = { index ->
            if (index == 0) awaitCancellation()
        }
    }

    @Test
    fun `cancelled quarantine leaves the source file and leaves quarantine unchanged`() = runTest {
        val gw = newGateway() as FakeStorageGateway
        armCancellation(gw)
        val job = launch { gw.quarantine(listOf(item(name = "a.gif"), item(name = "b.gif")), BatchId("b1")) }
        yield()
        job.cancelAndJoin()
        assertTrue(fileExists(pathOf("a.gif")))
        assertTrue(gw.quarantineContents().isEmpty())
    }

    @Test
    fun `moving an item into its own directory is a no-op, not a collision`() = runTest {
        val gw = newGateway()
        val original = item(name = "a.gif")
        assertEquals(MoveResult.Moved(original), gw.move(original, Destination(source())))
        assertTrue(fileExists(pathOf("a.gif")))
        assertEquals(1, gw.enumerate(source(), null).first().items.count { it.mime == "image/gif" })
    }

    @Test
    fun `colliding dotfile gets the suffix appended to the whole name`() = runTest {
        val gw = newGateway(listOf(item(name = ".hidden")))
        val dst = destinationWith(".hidden")
        val result = gw.move(item(name = ".hidden"), dst)
        val resolved = when (result) {
            is MoveResult.NameCollision -> result.resolvedTo
            else -> fail("expected MoveResult.NameCollision but was $result")
        }
        assertTrue(resolved.path!!.endsWith(".hidden (1)"))
    }
}
