package com.swipedelete.feature.deck.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SPEC 5.4 / 9.5 / M1-T11: the pure memory-ceiling state machine. Degradation
 * order is fixed — preloading is sacrificed before autoplay — and no sample,
 * however extreme, may ever throw (the alternative is an OOM).
 */
class AutoplayPolicyTest {

    /** An explicit 100 MiB RSS ceiling for the policy under test. */
    private val ceiling = 100L * 1024L * 1024L

    private fun policy() = AutoplayPolicy(memoryCeilingBytes = ceiling)

    @Test
    fun `under the ceiling autoplay and preloading are both allowed`() {
        val policy = policy()

        assertEquals(AutoplayLevel.NORMAL, policy.level)
        assertTrue(policy.allowsAutoplay())
        assertTrue(policy.allowsPreload())
    }

    @Test
    fun `crossing the ceiling drops preloading before autoplay`() {
        val policy = policy()

        val first = policy.onMemorySample(ceiling + 1)

        assertEquals(AutoplayLevel.DROP_PRELOAD, first, "preload goes first, never autoplay")
        assertFalse(policy.allowsPreload(), "preloading must stop at the ceiling")
        assertTrue(policy.allowsAutoplay(), "autoplay must survive the first crossing")

        val second = policy.onMemorySample(ceiling + 1)

        assertEquals(AutoplayLevel.DROP_AUTOPLAY, second, "sustained pressure then drops autoplay")
        assertFalse(policy.allowsAutoplay())
    }

    @Test
    fun `usage at exactly the ceiling is still normal`() {
        val policy = policy()

        assertEquals(AutoplayLevel.NORMAL, policy.onMemorySample(ceiling))
        assertTrue(policy.allowsPreload())
    }

    @Test
    fun `recovering below the ceiling restores normal`() {
        val policy = policy()
        policy.onMemorySample(ceiling + 1)
        policy.onMemorySample(ceiling + 1)
        assertEquals(AutoplayLevel.DROP_AUTOPLAY, policy.level)

        val recovered = policy.onMemorySample(0)

        assertEquals(AutoplayLevel.NORMAL, recovered)
        assertTrue(policy.allowsAutoplay())
        assertTrue(policy.allowsPreload())
    }

    @Test
    fun `a fling cancels autoplay until the deck settles`() {
        val policy = policy()
        assertTrue(policy.allowsAutoplay())

        policy.onFling()

        assertFalse(policy.allowsAutoplay(), "a fling must stop the card autoplaying")
        assertFalse(policy.allowsPreload(), "a fling must not keep preloading through the throw")

        policy.onSettled()

        assertTrue(policy.allowsAutoplay(), "the next settled card may autoplay again")
        assertTrue(policy.allowsPreload())
    }

    @Test
    fun `a fling never lowers the memory level`() {
        val policy = policy()

        policy.onFling()

        assertEquals(AutoplayLevel.NORMAL, policy.level, "cancellation is not memory pressure")
    }

    @Test
    fun `extreme usage values never throw`() {
        val policy = policy()

        for (usage in listOf(Long.MIN_VALUE, -1L, 0L, Long.MAX_VALUE)) {
            policy.onMemorySample(usage)
        }
    }

    @Test
    fun `a non-positive ceiling degrades rather than crashing`() {
        val policy = AutoplayPolicy(memoryCeilingBytes = 0L)

        assertEquals(AutoplayLevel.DROP_PRELOAD, policy.onMemorySample(1L))
    }
}
