package com.swipedelete.feature.dashboard.history

import com.swipedelete.core.common.formatBytes

enum class HistoryRowStatus(val text: String, val canRestore: Boolean) {
    Restorable("Can be restored", true),
    Restored("Restored", false),
    NoLongerRestorable("No longer restorable (emptied or missing)", false),
    Other("Recorded only", false),
}

data class HistoryRow(
    val id: String,
    val batchId: String,
    val path: String,
    val sizeBytes: Long,
    val createdAt: Long,
    val status: HistoryRowStatus,
    val kind: String,
) {
    val name: String get() = path.substringAfterLast('/')
}

data class HistoryBatch(val batchId: String, val createdAt: Long, val rows: List<HistoryRow>) {
    val itemCount: Int get() = rows.size
    val bytesText: String get() = formatBytes(rows.sumOf { it.sizeBytes })
}

sealed interface RestoreOutcome {
    data object Restored : RestoreOutcome
    data object Conflict : RestoreOutcome
    data class Failed(val reason: String) : RestoreOutcome
}

interface HistoryPort {
    suspend fun rows(): List<HistoryRow>
    suspend fun restore(rowId: String, keepBoth: Boolean): RestoreOutcome
}

fun historyBatches(rows: List<HistoryRow>): List<HistoryBatch> =
    rows.groupBy { it.batchId }
        .map { (batch, members) -> HistoryBatch(batch, members.minOf { it.createdAt }, members) }
        .sortedByDescending { it.createdAt }
