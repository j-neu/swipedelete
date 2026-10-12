package com.swipedelete.core.common

import kotlin.test.Test
import kotlin.test.assertEquals

class MeasuredFreedTest {

    @Test
    fun `freed is the increase in usable bytes`() {
        val m = measureFreed(usableBefore = 10_000L, usableAfter = 12_500L, expectedBytes = 3_000L)

        assertEquals(2_500L, m.freedBytes)
        assertEquals(2_500L, m.rawDeltaBytes)
        assertEquals(3_000L, m.expectedBytes)
    }

    @Test
    fun `a quarantine-only run frees nothing even though bytes are reclaimable`() {
        val m = measureFreed(usableBefore = 10_000L, usableAfter = 10_000L, expectedBytes = 4_000L)

        assertEquals(0L, m.freedBytes)
        assertEquals("0 B freed", formatFreed(m.freedBytes))
    }

    @Test
    fun `a negative delta is never reported as freed but stays available raw`() {
        val m = measureFreed(usableBefore = 10_000L, usableAfter = 9_000L, expectedBytes = 500L)

        assertEquals(0L, m.freedBytes)
        assertEquals(-1_000L, m.rawDeltaBytes)
    }
}
