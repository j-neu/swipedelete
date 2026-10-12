package com.swipedelete.core.common

/**
 * What an operation really freed, as the OS reports it (AC-9).
 *
 * [freedBytes] is never negative and never a sum of file sizes: it is the rise
 * in usable bytes across the operation. [expectedBytes] is what the caller
 * hoped to free, kept beside it so a shortfall is visible. [rawDeltaBytes]
 * keeps the signed change, because another app may have written meanwhile.
 * Render [freedBytes] only with [formatFreed].
 */
data class MeasuredFreed(val freedBytes: Long, val rawDeltaBytes: Long, val expectedBytes: Long)

fun measureFreed(usableBefore: Long, usableAfter: Long, expectedBytes: Long): MeasuredFreed {
    val delta = usableAfter - usableBefore
    return MeasuredFreed(freedBytes = maxOf(0L, delta), rawDeltaBytes = delta, expectedBytes = expectedBytes)
}

/** Reads the OS-reported usable bytes of a volume; an interface so tests stay on the JVM. */
fun interface UsableBytesSampler {
    fun usableBytes(): Long
}
