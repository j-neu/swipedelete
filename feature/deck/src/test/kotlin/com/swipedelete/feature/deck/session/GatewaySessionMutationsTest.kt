package com.swipedelete.feature.deck.session

import com.swipedelete.core.model.BatchId
import com.swipedelete.core.model.MediaItem
import com.swipedelete.core.storage.Failure
import com.swipedelete.core.storage.Metadata
import com.swipedelete.core.storage.QuarantineResult
import com.swipedelete.core.storage.StorageGateway
import com.swipedelete.core.storage.fake.FakeStorageGateway
import com.swipedelete.feature.deck.domain.mediaItem
import com.swipedelete.feature.deck.domain.sourceRef
import java.io.FileNotFoundException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * AC-8 in production: the destructive Apply path re-reads each staged item's
 * metadata through the gateway before it is destroyed, and reports a changed or
 * vanished item instead of acting on stale metadata.
 */
class GatewaySessionMutationsTest {

    private val gateway = FakeStorageGateway()
    private val source = sourceRef("src")

    init {
        gateway.addSource(source)
    }

    private fun item(size: Long) = mediaItem(
        "i1",
        "content://tree/src/i1.jpg",
        sizeBytes = size,
        sourceId = source.id,
    )

    @Test
    fun `an item that still matches the snapshot passes the re-check`() = runTest {
        val expected = item(size = 100L)
        gateway.register(expected)

        assertNull(GatewaySessionMutations(gateway).staleReason(expected))
    }

    @Test
    fun `a size change is reported as a visible reason`() = runTest {
        val expected = item(size = 100L)
        gateway.register(expected.copy(sizeBytes = 250L))

        val reason = GatewaySessionMutations(gateway).staleReason(expected)

        assertNotNull(reason)
        assertTrue(reason.contains("size"), "the reason must name what changed: $reason")
    }

    @Test
    fun `an unreadable item is aborted rather than destroyed`() = runTest {
        val unreadable = object : StorageGateway by gateway {
            override suspend fun metadata(item: MediaItem): Metadata = throw FileNotFoundException("gone")
        }

        assertNotNull(GatewaySessionMutations(unreadable).staleReason(item(size = 100L)))
    }

    @Test
    fun `a partial quarantine reports each refused item with its reason`() = runTest {
        val a = item(size = 100L)
        val refusing = object : StorageGateway by gateway {
            override suspend fun quarantine(items: List<MediaItem>, batch: BatchId): QuarantineResult =
                QuarantineResult.Partial(emptyList(), listOf(Failure(a.id, "protected_root")))
        }

        val outcome = GatewaySessionMutations(refusing).quarantine(listOf(a), BatchId("b"))

        assertTrue(outcome.moved.isEmpty())
        assertEquals(listOf(Failure(a.id, "protected_root")), outcome.failures)
    }

    @Test
    fun `insufficient space fails every item instead of moving none silently`() = runTest {
        val a = item(size = 100L)
        val full = object : StorageGateway by gateway {
            override suspend fun quarantine(items: List<MediaItem>, batch: BatchId): QuarantineResult =
                QuarantineResult.InsufficientSpace(neededBytes = 100L, freeBytes = 1L)
        }

        val outcome = GatewaySessionMutations(full).quarantine(listOf(a), BatchId("b"))

        assertTrue(outcome.moved.isEmpty())
        assertEquals(listOf(Failure(a.id, "insufficient_space")), outcome.failures)
    }
}
