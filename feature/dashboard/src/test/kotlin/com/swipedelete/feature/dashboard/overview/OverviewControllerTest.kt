package com.swipedelete.feature.dashboard.overview

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OverviewControllerTest {

    private class FakePort(
        var items: List<OverviewItem>,
        var capacity: CapacityInfo = CapacityInfo(totalBytes = 64L shl 30, freeBytes = 11L shl 30, reclaimableBytes = 0),
    ) : OverviewPort {
        override suspend fun items() = items
        override suspend fun capacity() = capacity
    }

    private val photo = OverviewItem("1", "a.jpg", "DCIM", "image/jpeg", 1024)

    @Test
    fun `free space is labelled as reported by Android, not as a file total`() = runTest {
        val c = OverviewController(FakePort(listOf(photo))).also { it.refresh() }

        assertEquals("11.0 GB free of 64.0 GB (reported by Android)", c.state.value.capacityText)
    }

    @Test
    fun `reclaimable bytes appear only when something is quarantined, and never as freed`() = runTest {
        val none = OverviewController(FakePort(listOf(photo))).also { it.refresh() }
        assertNull(none.state.value.reclaimableText)

        val held = OverviewController(FakePort(listOf(photo), CapacityInfo(100, 50, 2048))).also { it.refresh() }
        assertEquals("2.0 KB reclaimable in quarantine", held.state.value.reclaimableText)
    }

    @Test
    fun `the banner always says what is not counted`() = runTest {
        val c = OverviewController(FakePort(listOf(photo))).also { it.refresh() }

        val banner = c.state.value.exclusionBanner
        assertTrue("app data" in banner && "system" in banner, banner)
        assertTrue("folder" in banner, "it must offer the in-context grant: $banner")
    }

    @Test
    fun `an empty library says so instead of drawing empty charts`() = runTest {
        val c = OverviewController(FakePort(emptyList())).also { it.refresh() }

        assertTrue(c.state.value.overview!!.categories.isEmpty())
        assertEquals("No files found in the places this app can see.", c.state.value.emptyText)
    }

    @Test
    fun `opening a metric shows its files and closing returns`() = runTest {
        val c = OverviewController(FakePort(listOf(photo))).also { it.refresh() }
        val images = c.state.value.overview!!.categories.single()

        c.open(images)
        assertEquals(listOf(photo), c.state.value.drilldown?.rows)

        c.close()
        assertNull(c.state.value.drilldown)
        assertNotNull(c.state.value.overview)
    }
}
