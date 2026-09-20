package com.swipedelete.core.model

/**
 * Capability tier of a running install (SPEC 4.4). This is deliberately a pure
 * JVM type with no Android dependency: the tier is decided by the platform
 * permission state, but the domain model that features and the deck reason about
 * must stay platform-free.
 *
 * - [TIER_0]: default on first run. SAF tree grants (and the Photo Picker) only.
 * - [TIER_1]: `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` granted on API 33+
 *   (`READ_EXTERNAL_STORAGE` on older platforms).
 * - [TIER_2]: tier 1 plus All Files Access.
 *
 * Tiers are cumulative: a user at tier 2 can be assumed to hold everything a
 * tier-1 install holds.
 */
enum class Tier {
    TIER_0,
    TIER_1,
    TIER_2,
    ;

    /** True when this tier is at least [other]. */
    fun isAtLeast(other: Tier): Boolean = ordinal >= other.ordinal
}
