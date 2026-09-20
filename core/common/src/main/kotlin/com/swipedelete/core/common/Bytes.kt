package com.swipedelete.core.common

import java.util.Locale

private const val KIB = 1024
private val BYTE_UNITS = arrayOf("B", "KB", "MB", "GB", "TB")

/**
 * Renders a byte count using 1024-based units (B, KB, MB, GB, TB) with a single
 * decimal place, except that values below 1 KiB are whole bytes with no decimal.
 *
 * Formatting is pinned to [Locale.US] so the decimal separator is always a dot;
 * the AC-9 wording assertions depend on that.
 */
fun formatBytes(bytes: Long): String {
    if (bytes < KIB) return "$bytes B"

    var value = bytes.toDouble()
    var unit = 0
    while (value >= KIB && unit < BYTE_UNITS.lastIndex) {
        value /= KIB
        unit++
    }
    return String.format(Locale.US, "%.1f %s", value, BYTE_UNITS[unit])
}

/**
 * Renders bytes that are held in quarantine, i.e. not yet freed. Together with
 * [formatFreed] this is the single source of truth for the two AC-9 phrases;
 * no other function may render either of them.
 */
fun formatReclaimable(bytes: Long): String = "${formatBytes(bytes)} reclaimable"

/**
 * Renders bytes that have actually been deleted and measured as freed. See
 * [formatReclaimable] for the AC-9 contract.
 */
fun formatFreed(bytes: Long): String = "${formatBytes(bytes)} freed"

/**
 * Renders a duration as the two largest non-zero units joined by a single
 * space, using `d`/`h`/`m`/`s` suffixes. Anything under one second is `"0s"`.
 */
fun humanDuration(ms: Long): String {
    if (ms < 1000L) return "0s"

    val totalSeconds = ms / 1000L
    val days = totalSeconds / 86_400L
    val hours = (totalSeconds % 86_400L) / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L

    val parts = ArrayList<String>(4)
    if (days > 0) parts += "${days}d"
    if (hours > 0) parts += "${hours}h"
    if (minutes > 0) parts += "${minutes}m"
    if (seconds > 0) parts += "${seconds}s"
    return parts.take(2).joinToString(" ")
}
