package com.swipedelete.feature.deck.session

import com.swipedelete.core.model.BatchId
import com.swipedelete.core.model.ItemId
import com.swipedelete.core.model.SessionId
import com.swipedelete.core.storage.fake.FakeStorageGateway
import com.swipedelete.feature.deck.domain.DeckBuilder
import com.swipedelete.feature.deck.domain.DeckQuery
import com.swipedelete.feature.deck.domain.FakeScanPort
import com.swipedelete.feature.deck.domain.FixedClock
import com.swipedelete.feature.deck.domain.Gesture
import com.swipedelete.feature.deck.domain.mediaItem
import com.swipedelete.feature.deck.domain.page
import com.swipedelete.feature.deck.domain.sourceRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/**
 * SPEC 5.5 / 6.5 / AC-9: the summary reports the bytes this session actually
 * holds in quarantine as reclaimable, and "restore everything" brings back the
 * items this session quarantined instead of being a button that does nothing.
 */
class SessionRestoreTest {

    private val gateway = FakeStorageGateway()
    private val sessions = FakeDeckSessionDao()
    private val mutations = RecordingMutations()
    private val source = sourceRef("src")

    private val items = listOf(
        mediaItem("i1", "content://tree/src/i1.jpg", sizeBytes = 1000L, sourceId = source.id),
        mediaItem("i2", "content://tree/src/i2.jpg", sizeBytes = 500L, sourceId = source.id),
    )

    init {
        gateway.addSource(source)
    }

    private suspend fun controllerWithBothQuarantined(): SessionController {
        val controller = SessionController(
            sessions = sessions,
            builder = DeckBuilder({ FakeScanPort(page(items)) }, gateway, FixedClock(0L)),
            clock = FixedClock(0L),
            gate = MutationGate { },
            mutations = mutations,
            measuredFreed = { 0L },
            sessionIds = { SessionId("session-restore") },
            batches = { BatchId("batch-1") },
            gateway = gateway,
        )
        controller.start(DeckQuery.Folder(source.id))
        controller.decide(Gesture.DELETE)
        controller.decide(Gesture.DELETE)
        controller.applyStaged()
        return controller
    }

    @Test
    fun `after Apply the summary reports the bytes held in quarantine as reclaimable`() = runTest {
        val summary = controllerWithBothQuarantined().summary()

        assertEquals(1500L, summary.reclaimableBytes)
        assertEquals(2, summary.restorable)
    }

    @Test
    fun `restore everything brings back what this session quarantined`() = runTest {
        val controller = controllerWithBothQuarantined()

        val restored = controller.restoreEverything()

        assertEquals(2, restored)
        assertEquals(listOf(ItemId("i1"), ItemId("i2")), mutations.restored.single().map { it.itemId })
        val summary = controller.summary()
        assertEquals(0, summary.quarantined)
        assertEquals(0L, summary.reclaimableBytes)
        assertEquals(0, summary.restorable)
    }

    @Test
    fun `an item that could not be restored stays counted as quarantined`() = runTest {
        val controller = controllerWithBothQuarantined()
        mutations.restorable = { it.itemId == ItemId("i1") }

        val restored = controller.restoreEverything()

        assertEquals(1, restored)
        val summary = controller.summary()
        assertEquals(1, summary.quarantined)
        assertEquals(500L, summary.reclaimableBytes)
        assertEquals(1, summary.restorable)
    }
}
