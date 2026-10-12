package com.swipedelete.feature.dashboard.quarantine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmptyQuarantineConfirmationTest {

    @Test
    fun `nothing is permitted before the flow is walked`() {
        val flow = EmptyQuarantineConfirmation(itemCount = 3)

        assertEquals(EmptyQuarantineConfirmation.Step.Idle, flow.step)
        assertFalse(flow.confirm())
    }

    @Test
    fun `the first step warns that it cannot be undone and does not permit deletion`() {
        val flow = EmptyQuarantineConfirmation(itemCount = 3)

        flow.begin()

        assertEquals(EmptyQuarantineConfirmation.Step.Warning, flow.step)
        assertTrue("cannot be undone" in flow.warning)
        assertFalse(flow.confirm(), "a one-tap path must not delete")
    }

    @Test
    fun `permanent deletion is refused until the exact item count is typed`() {
        val flow = EmptyQuarantineConfirmation(itemCount = 12)
        flow.begin()
        flow.proceed()

        for (wrong in listOf("", "1", "13", "twelve", "12 files", "-12")) {
            flow.type(wrong)
            assertFalse(flow.confirm(), "typed '$wrong'")
        }
        flow.type(" 12 ")
        assertTrue(flow.confirm())
    }

    @Test
    fun `typing cannot skip the warning step`() {
        val flow = EmptyQuarantineConfirmation(itemCount = 2)
        flow.begin()

        flow.type("2")

        assertFalse(flow.confirm())
    }

    @Test
    fun `cancelling at either step permits nothing`() {
        val atWarning = EmptyQuarantineConfirmation(itemCount = 2).apply { begin(); cancel() }
        assertEquals(EmptyQuarantineConfirmation.Step.Idle, atWarning.step)
        assertFalse(atWarning.confirm())

        val atTyping = EmptyQuarantineConfirmation(itemCount = 2).apply { begin(); proceed(); type("2"); cancel() }
        assertEquals(EmptyQuarantineConfirmation.Step.Idle, atTyping.step)
        assertFalse(atTyping.confirm())
    }

    @Test
    fun `an empty quarantine cannot start the flow`() {
        val flow = EmptyQuarantineConfirmation(itemCount = 0)

        flow.begin()

        assertEquals(EmptyQuarantineConfirmation.Step.Idle, flow.step)
        assertFalse(flow.confirm())
    }
}
