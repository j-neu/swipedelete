package com.swipedelete.feature.deck.media

import com.swipedelete.core.model.ItemId
import com.swipedelete.feature.deck.ui.AutoplayLevel
import com.swipedelete.feature.deck.ui.AutoplayPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SPEC 5.4 / 9.5 / M1-T11: the deck's decoder budget. There is never more than
 * one active decoder plus one preloaded item, the memory ceiling releases the
 * preload before the active decoder, a fling cancels autoplay, and `onStop`
 * releases everything.
 */
class PreloaderTest {

    private class FakeDecoder : DeckMediaDecoder {
        var releaseCount = 0
        val released: Boolean get() = releaseCount > 0
        override fun release() {
            releaseCount++
        }
    }

    private val ceiling = 100L
    private val opened = mutableListOf<FakeDecoder>()

    private fun preloader(policy: AutoplayPolicy): Preloader = Preloader(policy) {
        FakeDecoder().also { opened += it }
    }

    private fun policy(): AutoplayPolicy = AutoplayPolicy(ceiling)

    private fun AutoplayPolicy.overCeiling(): AutoplayLevel = onMemorySample(ceiling + 1)

    @Test
    fun `a settled card opens one active decoder and preloads one next item`() {
        val preloader = preloader(policy())

        preloader.onCardSettled(ItemId("current"), ItemId("next"))

        assertEquals(1, preloader.activeDecoderCount)
        assertEquals(1, preloader.preloadedDecoderCount)
        assertEquals(2, opened.size)
    }

    @Test
    fun `dropping preload releases only the preloaded decoder`() {
        val policy = policy()
        val preloader = preloader(policy)
        preloader.onCardSettled(ItemId("current"), ItemId("next"))
        val active = opened[0]
        val preloaded = opened[1]

        preloader.onMemorySample(ceiling + 1)

        assertEquals(AutoplayLevel.DROP_PRELOAD, preloader.level)
        assertEquals(1, preloader.activeDecoderCount, "autoplay must still be running")
        assertEquals(0, preloader.preloadedDecoderCount)
        assertFalse(active.released, "the active decoder is dropped only at DROP_AUTOPLAY")
        assertTrue(preloaded.released, "the preloaded decoder must be released first")
    }

    @Test
    fun `dropping autoplay releases the active decoder too`() {
        val policy = policy()
        val preloader = preloader(policy)
        preloader.onCardSettled(ItemId("current"), ItemId("next"))
        val active = opened[0]

        preloader.onMemorySample(ceiling + 1)
        preloader.onMemorySample(ceiling + 1)

        assertEquals(AutoplayLevel.DROP_AUTOPLAY, preloader.level)
        assertEquals(0, preloader.activeDecoderCount)
        assertEquals(0, preloader.preloadedDecoderCount)
        assertTrue(active.released)
    }

    @Test
    fun `a settled card at DROP_PRELOAD opens the active decoder but not the preload`() {
        val policy = policy()
        policy.overCeiling()
        val preloader = preloader(policy)

        preloader.onCardSettled(ItemId("current"), ItemId("next"))

        assertEquals(1, preloader.activeDecoderCount)
        assertEquals(0, preloader.preloadedDecoderCount)
    }

    @Test
    fun `a fling cancels autoplay and releases every decoder`() {
        val policy = policy()
        val preloader = preloader(policy)
        preloader.onCardSettled(ItemId("current"), ItemId("next"))

        preloader.onFling()

        assertTrue(policy.autoplayCancelled)
        assertEquals(0, preloader.activeDecoderCount)
        assertEquals(0, preloader.preloadedDecoderCount)
        assertTrue(opened.all { it.released })
    }

    @Test
    fun `a card settled while autoplay is cancelled opens nothing`() {
        val preloader = preloader(policy())
        preloader.onFling()

        preloader.onCardSettled(ItemId("current"), ItemId("next"))

        assertEquals(0, preloader.activeDecoderCount)
        assertEquals(0, preloader.preloadedDecoderCount)
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `onStop releases every decoder`() {
        val preloader = preloader(policy())
        preloader.onCardSettled(ItemId("current"), ItemId("next"))

        preloader.onStop()

        assertEquals(0, preloader.activeDecoderCount)
        assertEquals(0, preloader.preloadedDecoderCount)
        assertTrue(opened.all { it.released })
    }

    @Test
    fun `crossing many cards never exceeds one active plus one preloaded`() {
        val preloader = preloader(policy())

        repeat(10) { index ->
            preloader.onCardSettled(ItemId("i$index"), ItemId("n$index"))

            assertTrue(preloader.activeDecoderCount <= 1, "active decoder leaked at $index")
            assertTrue(preloader.preloadedDecoderCount <= 1, "preloaded decoder leaked at $index")
        }

        assertEquals(2, opened.count { !it.released }, "only the newest pair may stay open")
        assertEquals(18, opened.count { it.released }, "every superseded decoder must be released")
    }

    @Test
    fun `a null decoder from the factory does not break the budget`() {
        val preloader = Preloader(policy()) { null }

        preloader.onCardSettled(ItemId("current"), ItemId("next"))

        assertEquals(0, preloader.activeDecoderCount)
        assertEquals(0, preloader.preloadedDecoderCount)
    }
}
