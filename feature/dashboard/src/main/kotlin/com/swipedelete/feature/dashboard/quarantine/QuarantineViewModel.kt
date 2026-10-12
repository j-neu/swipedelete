package com.swipedelete.feature.dashboard.quarantine

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swipedelete.core.common.UsableBytesSampler
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class QuarantineViewModel @Inject constructor(
    port: QuarantinePort,
    sampler: UsableBytesSampler,
) : ViewModel() {
    private val controller = QuarantineController(port, sampler) { System.currentTimeMillis() }

    val state: StateFlow<QuarantineUiState> = controller.state

    init {
        refresh()
    }

    fun refresh() = viewModelScope.launch { controller.refresh() }
    fun restoreAll() = viewModelScope.launch { controller.restoreAll() }
    fun beginEmpty() = controller.beginEmpty()
    fun proceedEmpty() = controller.proceedEmpty()
    fun typeCount(text: String) = controller.typeCount(text)
    fun cancelEmpty() = controller.cancelEmpty()
    fun confirmEmpty() = viewModelScope.launch { controller.confirmEmpty() }
}
