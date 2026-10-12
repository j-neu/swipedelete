package com.swipedelete.feature.dashboard.overview

import com.swipedelete.core.common.formatBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** AC-13: every number on the overview is derived from the list it opens. */
class DashboardDrilldownTest {

    private fun file(id: Int, mime: String, size: Long, folder: String = "DCIM/Camera", name: String = "f$id") =
        OverviewItem(id = "id$id", name = name, folder = folder, mime = mime, sizeBytes = size)

    private val items = listOf(
        file(1, "image/jpeg", 4_000, "DCIM/Camera"),
        file(2, "image/png", 1_000, "Pictures/Screenshots"),
        file(3, "video/mp4", 90_000, "DCIM/Camera"),
        file(4, "audio/mpeg", 5_000, "Music"),
        file(5, "application/pdf", 700, "Download"),
        file(6, "text/plain", 30, "Download"),
        file(7, "application/vnd.android.package-archive", 20_000, "Download"),
        file(8, "application/octet-stream", 123, "Download"),
        file(9, "image/jpeg", 2_000, "DCIM/Camera"),
    )

    private val overview = buildOverview(items)
    private val everyMetric: List<Metric> =
        overview.categories + overview.topFiles + overview.topFolders.map { it.metric }

    @Test
    fun `every metric shows exactly the bytes and count of its own list`() {
        assertTrue(everyMetric.isNotEmpty())
        everyMetric.forEach { m ->
            assertEquals(m.items.sumOf { it.sizeBytes }, m.bytes, m.label)
            assertEquals(formatBytes(m.items.sumOf { it.sizeBytes }), m.bytesText, m.label)
            assertEquals(m.items.size, m.count, m.label)
        }
    }

    @Test
    fun `opening a metric lists exactly its items`() {
        everyMetric.forEach { m -> assertEquals(m.items, drilldown(m).rows, m.label) }
    }

    @Test
    fun `categories partition the files - none lost, none counted twice`() {
        val all = overview.categories.flatMap { it.items }

        assertEquals(items.size, all.size)
        assertEquals(items.toSet(), all.toSet())
    }

    @Test
    fun `files are filed by type, and unrecognised ones stay a real list called Other`() {
        fun cat(label: String) = overview.categories.single { it.label == label }.items.map { it.id }.sorted()

        assertEquals(listOf("id1", "id2", "id9"), cat("Images"))
        assertEquals(listOf("id3"), cat("Video"))
        assertEquals(listOf("id4"), cat("Audio"))
        assertEquals(listOf("id5", "id6"), cat("Documents"))
        assertEquals(listOf("id7"), cat("APKs"))
        assertEquals(listOf("id8"), cat("Other"))
    }

    @Test
    fun `empty categories are left out rather than shown as zero`() {
        val only = buildOverview(listOf(file(1, "image/jpeg", 10)))

        assertEquals(listOf("Images"), only.categories.map { it.label })
    }

    @Test
    fun `top files are the largest, biggest first, and capped at 20`() {
        val many = (1..30).map { file(it, "image/jpeg", it * 100L) }

        val top = buildOverview(many).topFiles

        assertEquals(20, top.count)
        assertEquals(3_000L, top.items.first().sizeBytes)
        assertEquals(1_100L, top.items.last().sizeBytes)
    }

    @Test
    fun `top folders rank by total size and each folder opens its own files`() {
        val folders = overview.topFolders

        assertEquals(listOf("DCIM/Camera", "Download", "Music", "Pictures/Screenshots"), folders.map { it.metric.label })
        assertEquals(setOf("id1", "id3", "id9"), folders.first().metric.items.map { it.id }.toSet())
    }

    @Test
    fun `the overview names nothing it cannot list`() {
        assertEquals(emptyList(), buildOverview(emptyList()).categories)
        assertEquals(0, buildOverview(emptyList()).topFiles.count)
    }
}
