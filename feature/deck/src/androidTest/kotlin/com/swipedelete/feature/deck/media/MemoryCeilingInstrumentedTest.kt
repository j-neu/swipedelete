package com.swipedelete.feature.deck.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Debug
import com.swipedelete.core.model.ItemId
import com.swipedelete.feature.deck.ui.AutoplayLevel
import com.swipedelete.feature.deck.ui.AutoplayPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * SPEC 5.4 / 9.5 / M1-T11, device pass: the decoder budget and the RSS ceiling
 * against **real** `MediaCodec` instances and the device's own memory reading.
 *
 * [PreloaderTest] pins the budget arithmetic on the JVM with fakes; this asserts
 * the same invariants while real decoders are being allocated and released, and
 * that the explicit ceiling is read from the device rather than assumed.
 *
 * Device-only: it cannot run on the JVM. It skips rather than fails on a device
 * with no AVC decoder, mirroring [com.swipedelete.feature.viewer.VideoViewerInstrumentedTest].
 */
@RunWith(AndroidJUnit4::class)
class MemoryCeilingInstrumentedTest {

    @Test
    fun the_preloader_never_holds_more_than_one_active_plus_one_preloaded_decoder() {
        assumeTrue("no AVC decoder on this device", avcDecoderAvailable())
        val decoders = mutableListOf<FakeCountingDecoder>()
        val preloader = Preloader(AutoplayPolicy(currentRssBytes())) { openRealDecoder(decoders) }

        repeat(8) { index ->
            preloader.onCardSettled(ItemId("i$index"), ItemId("n$index"))

            assertTrue("active decoder count exceeded 1 at $index", preloader.activeDecoderCount <= 1)
            assertTrue(
                "preloaded decoder count exceeded 1 at $index",
                preloader.preloadedDecoderCount <= 1,
            )
        }

        assertEquals("only the newest pair may stay open", 2, decoders.count { !it.released })
        preloader.onStop()
        assertTrue("onStop must release every decoder", decoders.all { it.released })
    }

    @Test
    fun crossing_the_rss_ceiling_releases_the_preload_before_autoplay() {
        assumeTrue("no AVC decoder on this device", avcDecoderAvailable())
        val decoders = mutableListOf<FakeCountingDecoder>()
        val ceiling = currentRssBytes()
        val preloader = Preloader(AutoplayPolicy(ceiling)) { openRealDecoder(decoders) }
        preloader.onCardSettled(ItemId("current"), ItemId("next"))
        val active = decoders[0]
        val preloaded = decoders[1]

        val first = preloader.onMemorySample(ceiling + 1)

        assertEquals(AutoplayLevel.DROP_PRELOAD, first)
        assertFalse("the active decoder keeps playing at DROP_PRELOAD", active.released)
        assertTrue("the preloaded decoder is released first", preloaded.released)
        assertEquals(0, preloader.preloadedDecoderCount)

        preloader.onMemorySample(ceiling + 1)

        assertEquals(AutoplayLevel.DROP_AUTOPLAY, preloader.level)
        assertTrue("the active decoder is released only at DROP_AUTOPLAY", active.released)
        assertEquals(0, preloader.activeDecoderCount)

        preloader.onStop()
    }

    @Test
    fun onStop_releases_every_real_decoder() {
        assumeTrue("no AVC decoder on this device", avcDecoderAvailable())
        val decoders = mutableListOf<FakeCountingDecoder>()
        val preloader = Preloader(AutoplayPolicy(currentRssBytes())) { openRealDecoder(decoders) }
        preloader.onCardSettled(ItemId("current"), ItemId("next"))

        preloader.onStop()

        assertEquals(0, preloader.activeDecoderCount)
        assertEquals(0, preloader.preloadedDecoderCount)
        assertTrue(decoders.all { it.released })
    }

    /** A real AVC decoder that counts and records its release. */
    private class FakeCountingDecoder(private val codec: MediaCodec) : DeckMediaDecoder {
        var releaseCount = 0
        val released: Boolean get() = releaseCount > 0

        override fun release() {
            releaseCount++
            runCatching { codec.release() }
        }
    }

    private fun openRealDecoder(sink: MutableList<FakeCountingDecoder>): DeckMediaDecoder? = try {
        FakeCountingDecoder(MediaCodec.createDecoderByType(MIME_AVC)).also { sink += it }
    } catch (_: Exception) {
        null
    }

    /** The device's current resident set size, in bytes. */
    private fun currentRssBytes(): Long {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        return info.totalPss.toLong() * BYTES_PER_KILOBYTE
    }

    private companion object {
        const val MIME_AVC = MediaFormat.MIMETYPE_VIDEO_AVC
        const val BYTES_PER_KILOBYTE = 1024L

        fun avcDecoderAvailable(): Boolean = try {
            MediaCodec.createDecoderByType(MIME_AVC).also { it.release() }
            true
        } catch (_: Exception) {
            false
        }
    }
}
