package com.swipedelete.feature.deck.ui

import com.swipedelete.core.database.dao.SlotDao
import com.swipedelete.core.database.entity.SlotEntity
import com.swipedelete.core.model.ItemId
import com.swipedelete.core.model.MediaItem
import com.swipedelete.core.storage.fake.FakeStorageGateway
import com.swipedelete.feature.deck.domain.DeckBuilder
import com.swipedelete.feature.deck.domain.FakeScanPort
import com.swipedelete.feature.deck.domain.FixedClock
import com.swipedelete.feature.deck.domain.Gesture
import com.swipedelete.feature.deck.domain.mediaItem
import com.swipedelete.feature.deck.domain.page
import com.swipedelete.feature.deck.domain.sourceRef
import com.swipedelete.feature.deck.session.FakeDeckSessionDao
import com.swipedelete.feature.deck.session.MutationGate
import com.swipedelete.feature.deck.session.RecordingMutations
import com.swipedelete.feature.deck.session.SessionMutations
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/**
 * CRITICAL 1: the assembled deck view model owns the deck, starts on a real
 * source, exposes the current item, and reads bytes through the gateway the
 * viewer uses — not the throwing placeholder the review found.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeckViewModelTest {

    private val gateway = FakeStorageGateway()
    private val source = sourceRef("src")
    private val items = listOf(
        mediaItem("i1", "content://tree/src/i1.jpg", sourceId = source.id),
        mediaItem("i2", "content://tree/src/i2.jpg", sourceId = source.id),
    )

    init {
        gateway.addSource(source)
        items.forEach { gateway.register(it, "bytes-${it.id.value}".toByteArray()) }
    }

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        truncated: Boolean = false,
        mutations: SessionMutations = RecordingMutations(),
    ) = DeckViewModel(
        builder = DeckBuilder(
            scanner = { FakeScanPort(page(items, truncated = truncated)) },
            gateway = gateway,
            clock = FixedClock(0L),
        ),
        gateway = gateway,
        sessions = FakeDeckSessionDao(),
        slots = FakeSlotDao(),
        gate = MutationGate { },
        mutations = mutations,
        clock = FixedClock(0L),
    )

    @Test
    fun `the assembled deck starts on the first source and exposes the current item`() {
        val vm = viewModel()

        assertEquals(listOf(ItemId("i1"), ItemId("i2")), vm.state.value.items.map { it.id })
        assertEquals(ItemId("i1"), vm.state.value.current?.id, "the deck must present a real card")
    }

    @Test
    fun `the viewer stream comes from the gateway, not a placeholder`() {
        val vm = viewModel()
        val current = requireNotNull(vm.state.value.current)

        val stream = vm.openStream(current)

        assertEquals("bytes-i1", String(stream.readBytes()), "the viewer must read through the gateway")
    }

    @Test
    fun `a capped scan surfaces a partial deck with the scanned count`() {
        val vm = viewModel(truncated = true)

        assertTrue(vm.state.value.partialDeck, "Task 30: a capped walk must be observable")
        assertEquals(items.size, vm.state.value.scanned)
    }

    @Test
    fun `Apply surfaces the items AC-8 aborted, and keeps them staged`() {
        val stale = object : SessionMutations {
            override suspend fun quarantine(items: List<MediaItem>, batch: com.swipedelete.core.model.BatchId): Int =
                items.size

            override suspend fun restoreSession(session: com.swipedelete.core.model.SessionId): Int = 0

            override suspend fun staleReason(item: MediaItem): String? =
                if (item.id == ItemId("i1")) "changed on disk" else null
        }
        val vm = viewModel(mutations = stale)

        vm.decide(Gesture.DELETE)
        vm.applyStaged()

        assertEquals(listOf(ItemId("i1")), vm.state.value.aborted.map { it.item.id })
        assertEquals(listOf(ItemId("i1")), vm.state.value.staged.map { it.id }, "an aborted item stays staged")
    }

    private class FakeSlotDao : SlotDao {
        private val slots = MutableStateFlow<List<SlotEntity>>(emptyList())
        override suspend fun upsert(slot: SlotEntity) {
            slots.value = slots.value.filterNot { it.id == slot.id } + slot
        }

        override suspend fun upsertAll(slots: List<SlotEntity>) {
            this.slots.value = slots
        }

        override suspend fun all(): List<SlotEntity> = slots.value
        override fun observeAll(): Flow<List<SlotEntity>> = slots
        override suspend fun byId(id: Int): SlotEntity? = slots.value.firstOrNull { it.id == id }
        override suspend fun delete(id: Int) {
            slots.value = slots.value.filterNot { it.id == id }
        }
    }
}
