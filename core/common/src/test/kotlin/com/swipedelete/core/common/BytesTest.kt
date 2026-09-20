package com.swipedelete.core.common

import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class BytesTest {
    private val originalLocale = Locale.getDefault()

    @AfterTest
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun `formatBytes uses binary units with one decimal`() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("4.1 GB", formatBytes(4_400_000_000))
    }

    @Test
    fun `formatBytes renders sub-kibibyte values as whole bytes with no decimal`() {
        assertEquals("512 B", formatBytes(512))
        assertEquals("1023 B", formatBytes(1023))
    }

    @Test
    fun `formatBytes uses 1024-based units labelled B KB MB GB TB`() {
        assertEquals("1.0 MB", formatBytes(1024L * 1024))
        assertEquals("1.0 GB", formatBytes(1024L * 1024 * 1024))
        assertEquals("1.0 TB", formatBytes(1024L * 1024 * 1024 * 1024))
    }

    @Test
    fun `reclaimable and freed wording are distinct and never interchangeable`() {
        assertEquals("4.1 GB reclaimable", formatReclaimable(4_400_000_000))
        assertEquals("4.1 GB freed", formatFreed(4_400_000_000))
        assertEquals("0 B reclaimable", formatReclaimable(0))
        assertEquals("0 B freed", formatFreed(0))
    }

    @Test
    fun `byte formatting is locale independent and always uses a dot decimal`() {
        Locale.setDefault(Locale.GERMANY)
        assertEquals("4.1 GB", formatBytes(4_400_000_000))
        assertEquals("4.1 GB freed", formatFreed(4_400_000_000))
        assertEquals("0 B", formatBytes(0))

        Locale.setDefault(Locale.FRANCE)
        assertEquals("1.0 KB", formatBytes(1024))
    }

    @Test
    fun `humanDuration renders the two largest non-zero units`() {
        assertEquals("0s", humanDuration(0))
        assertEquals("0s", humanDuration(999))
        assertEquals("45s", humanDuration(45_000))
        assertEquals("1m 30s", humanDuration(90_000))
        assertEquals("1h", humanDuration(3_600_000))
        assertEquals("1d 1h", humanDuration(90_000_000))
    }
}
