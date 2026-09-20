package com.swipedelete.feature.settings.onboarding

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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * SPEC 4.5 / AC-19. Three things must hold for the "pick your five folders"
 * flow: it is skippable with zero slots and still usable, the slots it stores
 * survive a process/view-model recreation, and a sixth slot is refused
 * explicitly rather than silently dropped.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val clock = FakeClock()

    @BeforeTest
    fun setUpMainDispatcher() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun restoreMainDispatcher() {
        Dispatchers.resetMain()
    }

    private fun viewModel(slots: FakeSlotDao): OnboardingViewModel {
        val store = FakeGrantStore()
        return OnboardingViewModel(
            slots = slots,
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
    fun `onboarding is skippable and the app remains usable with zero slots`() = runTest {
        val viewModel = viewModel(FakeSlotDao())
        advanceUntilIdle()
        assertTrue(viewModel.state.value.slots.isEmpty(), "a fresh install starts with no slots")

        viewModel.skip()

        val state = viewModel.state.value
        assertTrue(state.completed, "skipping completes onboarding")
        assertTrue(state.slots.isEmpty(), "AC-19: a completed flow with zero slots is still a usable app")
        assertNull(state.error, "skipping must not set an error or dead-end flag")
        assertFalse(state.slotLimitReached, "zero slots must never read as a limit failure")
    }

    @Test
    fun `slots persist across a view model recreation`() = runTest {
        val dao = FakeSlotDao()
        val names = listOf("Download", "Camera", "Screenshots", "Music", "Export")
        val first = viewModel(dao)
        advanceUntilIdle()
        names.forEachIndexed { index, name ->
            assertTrue(first.addSlot(source(name, index)), "slot ${index + 1} must be accepted")
        }
        assertEquals(names, first.state.value.slots.map { it.name })

        // A new view model over the same DAO is what "process recreation" looks like.
        val recreated = viewModel(dao)
        advanceUntilIdle()

        assertEquals(names, recreated.state.value.slots.map { it.name }, "five slots survive recreation")
        assertEquals(
            listOf(0, 1, 2, 3, 4),
            recreated.state.value.slots.map { it.sortOrder },
            "slot order survives recreation",
        )
    }

    @Test
    fun `a sixth slot beyond five is refused`() = runTest {
        val dao = FakeSlotDao()
        val viewModel = viewModel(dao)
        advanceUntilIdle()
        repeat(MAX_SLOTS) { index ->
            assertTrue(viewModel.addSlot(source("Slot$index", index)))
        }
        val accepted = viewModel.state.value.slots
        assertEquals(MAX_SLOTS, accepted.size)

        val acceptedSixth = viewModel.addSlot(source("Sixth", MAX_SLOTS))

        assertFalse(acceptedSixth, "the sixth slot must be refused")
        val state = viewModel.state.value
        assertIs<OnboardingError.SlotLimitReached>(
            state.error,
            "the refusal must be explicit, not a silent drop",
        )
        assertEquals(MAX_SLOTS, state.error.max)
        assertEquals(accepted, state.slots, "the existing five slots are unchanged")
        assertEquals(MAX_SLOTS, dao.all().size, "nothing was written for the refused slot")
    }

    @Test
    fun `removing a slot frees room for a sixth`() = runTest {
        val dao = FakeSlotDao()
        val viewModel = viewModel(dao)
        advanceUntilIdle()
        repeat(MAX_SLOTS) { index -> viewModel.addSlot(source("Slot$index", index)) }

        viewModel.removeSlot(viewModel.state.value.slots.first().id)
        advanceUntilIdle()

        assertEquals(MAX_SLOTS - 1, viewModel.state.value.slots.size)
        assertTrue(viewModel.addSlot(source("Replacement", 10)))
        assertEquals(MAX_SLOTS, viewModel.state.value.slots.size)
    }
}
