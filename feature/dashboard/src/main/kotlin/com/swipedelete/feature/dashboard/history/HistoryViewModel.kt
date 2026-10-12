package com.swipedelete.feature.dashboard.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class HistoryViewModel @Inject constructor(port: HistoryPort) : ViewModel() {
    private val controller = HistoryController(port)

    val state: StateFlow<HistoryUiState> = controller.state

    init {
        viewModelScope.launch { controller.refresh() }
    }

    fun restore(rowId: String) = viewModelScope.launch { controller.restore(rowId) }
    fun keepBoth() = viewModelScope.launch { controller.keepBoth() }
    fun skip() = controller.skipConflict()
    fun export(uri: String, format: ExportFormat) = viewModelScope.launch { controller.export(uri, format) }
}
