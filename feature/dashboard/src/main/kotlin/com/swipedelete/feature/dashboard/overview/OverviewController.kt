package com.swipedelete.feature.dashboard.overview

import com.swipedelete.core.common.formatBytes
import com.swipedelete.core.common.formatReclaimable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Volume figures as Android reports them. They are device facts, not sums of files. */
data class CapacityInfo(val totalBytes: Long, val freeBytes: Long, val reclaimableBytes: Long)

interface OverviewPort {
    suspend fun items(): List<OverviewItem>
    suspend fun capacity(): CapacityInfo
}

data class OverviewUiState(
    val loading: Boolean = true,
    val overview: Overview? = null,
    val capacityText: String = "",
    val reclaimableText: String? = null,
    val emptyText: String? = null,
    val exclusionBanner: String = EXCLUSION_BANNER,
    val drilldown: Drilldown? = null,
)

const val EXCLUSION_BANNER: String =
    "Counts only media and folders this app can see. Not included: app data and system files, " +
        "which Android does not let it measure. Add a folder in Settings to include more."

class OverviewController(private val port: OverviewPort) {
    private val _state = MutableStateFlow(OverviewUiState())
    val state: StateFlow<OverviewUiState> = _state.asStateFlow()

    suspend fun refresh() {
        val overview = buildOverview(port.items())
        val capacity = port.capacity()
        _state.value = _state.value.copy(
            loading = false,
            overview = overview,
            capacityText = "${formatBytes(capacity.freeBytes)} free of ${formatBytes(capacity.totalBytes)} (reported by Android)",
            reclaimableText = capacity.reclaimableBytes.takeIf { it > 0 }
                ?.let { "${formatReclaimable(it)} in quarantine" },
            emptyText = if (overview.categories.isEmpty()) "No files found in the places this app can see." else null,
        )
    }

    fun open(metric: Metric) {
        _state.value = _state.value.copy(drilldown = drilldown(metric))
    }

    fun close() {
        _state.value = _state.value.copy(drilldown = null)
    }
}
