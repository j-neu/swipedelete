package com.swipedelete.feature.deck.ui

/**
 * SPEC 5.4 / 9.5: how much automatic media work the deck is currently allowed to
 * do. The order is deliberate — preloading is sacrificed before autoplay — so a
 * card that is already playing is the last thing to stop.
 */
enum class AutoplayLevel {
    /** Autoplay the current card and preload the next. */
    NORMAL,

    /** Autoplay only; the next item is not decoded ahead of time. */
    DROP_PRELOAD,

    /** Nothing decodes automatically: no autoplay, no preloading. */
    DROP_AUTOPLAY,
}

/**
 * SPEC 9.5 / M1-T11: the pure memory-ceiling state machine behind deck autoplay.
 *
 * It is driven by an explicit byte ceiling (`memoryCeilingBytes`, meant to be an
 * RSS budget) rather than by catching an `OutOfMemoryError`: each sample above
 * the ceiling drops one level in the fixed order [AutoplayLevel.NORMAL] →
 * [AutoplayLevel.DROP_PRELOAD] → [AutoplayLevel.DROP_AUTOPLAY], and any sample
 * at or below the ceiling recovers to [AutoplayLevel.NORMAL]. It never throws,
 * whatever the sample value, so a garbage or sentinel reading degrades rather
 * than crashing the deck.
 *
 * A fling separately cancels autoplay for the duration of the throw; that is a
 * user-intent pause, not memory pressure, so it does not move [level].
 */
class AutoplayPolicy(
    /** The ceiling as supplied; the effective ceiling never goes below zero. */
    val memoryCeilingBytes: Long,
) {

    private val effectiveCeilingBytes: Long = memoryCeilingBytes.coerceAtLeast(0L)

    /** The current degradation level; the UI reads this to react. */
    var level: AutoplayLevel = AutoplayLevel.NORMAL
        private set

    /** True from [onFling] until the deck settles on a card again. */
    var autoplayCancelled: Boolean = false
        private set

    /**
     * Records the current memory usage and returns the (possibly degraded) level.
     * One step is dropped per over-ceiling sample; usage within the ceiling
     * recovers fully. Never throws.
     */
    fun onMemorySample(usedBytes: Long): AutoplayLevel {
        level = if (usedBytes > effectiveCeilingBytes) escalate(level) else AutoplayLevel.NORMAL
        return level
    }

    /** The user flung the deck: stop autoplay now. */
    fun onFling() {
        autoplayCancelled = true
    }

    /** The deck settled on a new card: autoplay may resume under the current level. */
    fun onSettled() {
        autoplayCancelled = false
    }

    fun allowsAutoplay(): Boolean =
        !autoplayCancelled && level != AutoplayLevel.DROP_AUTOPLAY

    fun allowsPreload(): Boolean =
        !autoplayCancelled && level == AutoplayLevel.NORMAL

    private fun escalate(current: AutoplayLevel): AutoplayLevel = when (current) {
        AutoplayLevel.NORMAL -> AutoplayLevel.DROP_PRELOAD
        AutoplayLevel.DROP_PRELOAD, AutoplayLevel.DROP_AUTOPLAY -> AutoplayLevel.DROP_AUTOPLAY
    }
}
