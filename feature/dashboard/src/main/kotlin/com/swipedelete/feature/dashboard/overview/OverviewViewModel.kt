package com.swipedelete.feature.dashboard.overview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class OverviewViewModel @Inject constructor(port: OverviewPort) : ViewModel() {
    private val controller = OverviewController(port)

    val state: StateFlow<OverviewUiState> = controller.state

    init {
        viewModelScope.launch { controller.refresh() }
    }

    fun open(metric: Metric) = controller.open(metric)
    fun close() = controller.close()
}
