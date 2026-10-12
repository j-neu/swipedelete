package com.swipedelete.feature.dashboard.quarantine

import com.swipedelete.core.common.formatReclaimable
import com.swipedelete.core.common.humanDuration

/** One quarantined file, as far as the card needs to know. */
data class HeldItem(val sizeBytes: Long, val expiresAt: Long)

/** What the dashboard's quarantine card shows (SPEC §7.3, AC-9). */
sealed interface QuarantineCard {

    data object Empty : QuarantineCard {
        val headline: String = "Nothing in quarantine"
    }

    data class Held(
        val itemCount: Int,
        val bytesText: String,
        val notice: String,
        val expiryText: String,
    ) : QuarantineCard
}

fun quarantineCard(items: List<HeldItem>, nowMillis: Long): QuarantineCard {
    if (items.isEmpty()) return QuarantineCard.Empty
    val soonest = items.minOf { it.expiresAt } - nowMillis
    return QuarantineCard.Held(
        itemCount = items.size,
        bytesText = formatReclaimable(items.sumOf { it.sizeBytes }),
        notice = "These bytes are not yet reclaimed. The space comes back only when you empty the quarantine.",
        expiryText = if (soonest > 0) {
            "Soonest expiry in ${humanDuration(soonest)}"
        } else {
            "Past its restore window, kept until you empty the quarantine"
        },
    )
}
