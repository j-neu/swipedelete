package com.swipedelete.feature.dashboard.history

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RestoreConflict(val rowId: String, val name: String)

data class HistoryUiState(
    val batches: List<HistoryBatch> = emptyList(),
    val conflict: RestoreConflict? = null,
    val message: String? = null,
)

/** Drives the history screen. A restore never overwrites: a clash opens a choice (AC-17). */
class HistoryController(private val port: HistoryPort) {
    private val _state = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    suspend fun refresh() {
        _state.value = _state.value.copy(batches = historyBatches(port.rows()))
    }

    suspend fun restore(rowId: String) {
        when (val outcome = port.restore(rowId, keepBoth = false)) {
            RestoreOutcome.Restored -> {
                refresh()
                _state.value = _state.value.copy(message = "Restored")
            }
            RestoreOutcome.Conflict -> {
                val name = _state.value.batches.flatMap { it.rows }.firstOrNull { it.id == rowId }?.name.orEmpty()
                _state.value = _state.value.copy(conflict = RestoreConflict(rowId, name))
            }
            is RestoreOutcome.Failed -> {
                refresh()
                _state.value = _state.value.copy(message = stillHeld(outcome.reason))
            }
        }
    }

    suspend fun keepBoth() {
        val conflict = _state.value.conflict ?: return
        _state.value = _state.value.copy(conflict = null)
        when (val outcome = port.restore(conflict.rowId, keepBoth = true)) {
            RestoreOutcome.Restored -> {
                refresh()
                _state.value = _state.value.copy(message = "Restored beside the existing file")
            }
            RestoreOutcome.Conflict ->
                _state.value = _state.value.copy(message = "Could not keep both here. The file is still in quarantine.")
            is RestoreOutcome.Failed ->
                _state.value = _state.value.copy(message = stillHeld(outcome.reason))
        }
    }

    fun skipConflict() {
        _state.value = _state.value.copy(conflict = null)
    }

    private fun stillHeld(reason: String) = "Could not restore: $reason. The file is still in quarantine."
}
