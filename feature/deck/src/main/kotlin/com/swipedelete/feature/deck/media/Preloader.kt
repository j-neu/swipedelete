package com.swipedelete.feature.deck.media

import com.swipedelete.core.model.ItemId
import com.swipedelete.feature.deck.ui.AutoplayLevel
import com.swipedelete.feature.deck.ui.AutoplayPolicy

/**
 * One decoder-backed media handle (a Media3 `ExoPlayer`, a `MediaCodec`, an
 * `ImageDecoder`-owned surface, …). [release] must be idempotent: the preloader
 * enforces the budget, but a double release must not be a caller error.
 */
fun interface DeckMediaDecoder {
    fun release()
}

/**
 * SPEC 5.4 / 9.5 / M1-T11: the deck's decoder budget.
 *
 * It keeps at most **one active decoder plus one preloaded item**, delegates the
 * memory ceiling and fling cancellation to [AutoplayPolicy], and releases every
 * decoder on `onStop`. The ceiling degrades in [AutoplayPolicy]'s fixed order:
 * crossing it releases the preload first while the active card keeps playing,
 * and only a further crossing stops autoplay. A fling is a deliberate release
 * of both, and the next settle re-opens what the current level allows.
 */
class Preloader(
    private val policy: AutoplayPolicy,
    private val openDecoder: (ItemId) -> DeckMediaDecoder?,
) {

    private var activeDecoder: DeckMediaDecoder? = null
    private var activeItem: ItemId? = null
    private var preloadedDecoder: DeckMediaDecoder? = null
    private var preloadedItem: ItemId? = null

    /** The current memory level, forwarded from [policy] for the UI. */
    val level: AutoplayLevel get() = policy.level

    val activeDecoderCount: Int get() = if (activeDecoder != null) 1 else 0

    val preloadedDecoderCount: Int get() = if (preloadedDecoder != null) 1 else 0

    /**
     * The deck settled on [item], with [nextItem] as its successor. Opens the
     * active decoder (when autoplay is allowed) and preloads the next item
     * (when preloading is allowed), releasing any decoder that no longer
     * belongs to this pair.
     */
    fun onCardSettled(item: ItemId, nextItem: ItemId?) {
        if (activeItem != null && activeItem != item) releaseActive()
        if (preloadedItem != null && preloadedItem != nextItem) releasePreloaded()
        if (!policy.allowsAutoplay()) releaseActive()
        if (!policy.allowsPreload()) releasePreloaded()

        if (activeDecoder == null && policy.allowsAutoplay()) {
            openDecoder(item)?.let {
                activeDecoder = it
                activeItem = item
            }
        }
        if (preloadedDecoder == null && nextItem != null && policy.allowsPreload()) {
            openDecoder(nextItem)?.let {
                preloadedDecoder = it
                preloadedItem = nextItem
            }
        }
    }

    /**
     * Records a memory sample with [policy] and releases whatever the new level
     * no longer allows. Returns the level so the caller can surface it.
     */
    fun onMemorySample(usedBytes: Long): AutoplayLevel {
        val sampled = policy.onMemorySample(usedBytes)
        if (!policy.allowsPreload()) releasePreloaded()
        if (!policy.allowsAutoplay()) releaseActive()
        return sampled
    }

    /** The user flung the deck: cancel autoplay and free both decoders. */
    fun onFling() {
        policy.onFling()
        releaseActive()
        releasePreloaded()
    }

    /** The surface stopped: release every decoder (SPEC 5.4). */
    fun onStop() {
        releaseActive()
        releasePreloaded()
        policy.onSettled()
    }

    private fun releaseActive() {
        activeDecoder?.release()
        activeDecoder = null
        activeItem = null
    }

    private fun releasePreloaded() {
        preloadedDecoder?.release()
        preloadedDecoder = null
        preloadedItem = null
    }
}
