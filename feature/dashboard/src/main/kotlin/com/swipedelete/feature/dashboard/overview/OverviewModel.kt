package com.swipedelete.feature.dashboard.overview

import com.swipedelete.core.common.formatBytes

/** One file as the overview sees it. */
data class OverviewItem(val id: String, val name: String, val folder: String, val mime: String, val sizeBytes: Long)

/**
 * A figure on the dashboard (E1). Its bytes and count are computed from [items]
 * and nothing else, so tapping it can always show exactly what it adds up.
 */
data class Metric(val label: String, val items: List<OverviewItem>) {
    val bytes: Long get() = items.sumOf { it.sizeBytes }
    val count: Int get() = items.size
    val bytesText: String get() = formatBytes(bytes)
}

data class FolderMetric(val metric: Metric)

data class Overview(val categories: List<Metric>, val topFiles: Metric, val topFolders: List<FolderMetric>)

/** What opening a metric shows: its files, one row each. */
data class Drilldown(val title: String, val rows: List<OverviewItem>)

fun drilldown(metric: Metric): Drilldown = Drilldown(metric.label, metric.items)

private const val TOP = 20

private val CATEGORY_ORDER = listOf("Images", "Video", "Audio", "Documents", "APKs", "Other")

private val DOCUMENT_MIMES = setOf(
    "application/pdf", "application/msword", "application/rtf", "application/epub+zip",
)

internal fun categoryOf(mime: String): String = when {
    mime.startsWith("image/") -> "Images"
    mime.startsWith("video/") -> "Video"
    mime.startsWith("audio/") -> "Audio"
    mime == "application/vnd.android.package-archive" -> "APKs"
    mime.startsWith("text/") || mime in DOCUMENT_MIMES ||
        mime.startsWith("application/vnd.openxmlformats") ||
        mime.startsWith("application/vnd.ms-") ||
        mime.startsWith("application/vnd.oasis") -> "Documents"
    else -> "Other"
}

/**
 * Builds the overview from the files that were actually enumerated. "Other" is
 * the unrecognised files we did see, a real list, never a computed remainder of
 * the volume (E3).
 */
fun buildOverview(items: List<OverviewItem>): Overview {
    val byCategory = items.groupBy { categoryOf(it.mime) }
    val categories = CATEGORY_ORDER.mapNotNull { label -> byCategory[label]?.let { Metric(label, it) } }
    val topFiles = Metric("Largest files", items.sortedByDescending { it.sizeBytes }.take(TOP))
    val topFolders = items.groupBy { it.folder }
        .map { (folder, files) -> Metric(folder, files) }
        .sortedWith(compareByDescending<Metric> { it.bytes }.thenBy { it.label })
        .take(TOP)
        .map(::FolderMetric)
    return Overview(categories, topFiles, topFolders)
}
