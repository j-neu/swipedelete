package com.swipedelete.feature.settings.onboarding

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.swipedelete.core.database.SwipeDeleteDatabase
import com.swipedelete.core.model.SourceId
import com.swipedelete.core.model.SourceKind
import com.swipedelete.core.model.SourceRef
import com.swipedelete.feature.settings.grants.FakeClock
import com.swipedelete.feature.settings.grants.FakeGrantMutator
import com.swipedelete.feature.settings.grants.FakeGrantStore
import com.swipedelete.feature.settings.grants.SourceGrantRepository
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The same onboarding rules as [OnboardingViewModelTest], but persisted through
 * the **real** Room `SlotDao` (an in-memory `SwipeDeleteDatabase`), so "the five
 * slots persist" is evidenced against the shipped schema rather than a fake.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingRoomPersistenceTest {

    private val dispatcher = StandardTestDispatcher()
    private val clock = FakeClock()
    private lateinit var database: SwipeDeleteDatabase

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SwipeDeleteDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        database.close()
    }

    private fun viewModel(): OnboardingViewModel {
        val store = FakeGrantStore()
        return OnboardingViewModel(
            slots = database.slotDao(),
            grants = SourceGrantRepository(store, FakeGrantMutator(store, clock), clock),
        )
    }

    private fun source(name: String, index: Int): SourceRef = SourceRef(
        id = SourceId("content://tree/$name"),
        treeUri = "content://tree/$name",
        displayName = name,
        access = SourceRef.Access.RW,
        kind = SourceKind.SAF,
        itemCount = null,
        totalBytes = null,
        grantedAt = index.toLong(),
    )

    @Test
    fun `five slots are written to Room and survive a view model recreation`() = runTest {
        val names = listOf("Download", "Camera", "Screenshots", "Music", "Export")
        val first = viewModel()
        advanceUntilIdle()
        names.forEachIndexed { index, name -> assertTrue(first.addSlot(source(name, index))) }

        val rows = database.slotDao().all()
        assertEquals(names, rows.map { it.name }, "the real Room slots table holds the five folders")
        assertEquals(listOf(0, 1, 2, 3, 4), rows.map { it.sortOrder })

        val recreated = viewModel()
        // Room's suspend DAO resolves on its own executor, so the init reload is
        // awaited through the state flow rather than by advancing virtual time.
        val restored = recreated.state.first { it.slots.size == MAX_SLOTS }.slots.map { it.name }
        assertEquals(names, restored)
    }

    @Test
    fun `a sixth slot writes nothing to Room`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        repeat(MAX_SLOTS) { index -> assertTrue(viewModel.addSlot(source("Slot$index", index))) }

        assertFalse(viewModel.addSlot(source("Sixth", MAX_SLOTS)))

        assertEquals(MAX_SLOTS, database.slotDao().all().size, "the refused slot must not reach Room")
        assertTrue(viewModel.state.value.slotLimitReached)
    }
}
