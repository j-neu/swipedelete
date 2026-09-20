package com.swipedelete.feature.settings.onboarding

/**
 * SPEC 4.5: the five destination slots in the footer *are* five persisted SAF
 * tree grants. Five is the product's slot limit; the grant cap
 * (`SourceGrantRepository.MAX_GRANTS`) is a separate, larger limit on all
 * persistence grants.
 */
const val MAX_SLOTS: Int = 5
