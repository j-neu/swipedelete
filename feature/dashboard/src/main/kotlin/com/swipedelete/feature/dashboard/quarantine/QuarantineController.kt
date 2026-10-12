package com.swipedelete.feature.dashboard.quarantine

import com.swipedelete.core.common.UsableBytesSampler
import com.swipedelete.core.common.formatBytes
import com.swipedelete.core.common.formatFreed
import com.swipedelete.core.common.measureFreed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class QuarantineUiState(
    val card: QuarantineCard = QuarantineCard.Empty,
    val step: EmptyQuarantineConfirmation.Step = EmptyQuarantineConfirmation.Step.Idle,
    val warning: String = "",
    val typed: String = "",
    val busy: Boolean = false,
    val message: String? = null,
)

/**
 * Drives the quarantine card. The permanent delete is reachable only through
 * [beginEmpty] → [proceedEmpty] → [typeCount] → [confirmEmpty]; the freed figure
 * it reports is the OS-measured change in usable bytes (AC-9), never file sizes.
 */
class QuarantineController(
    private val port: QuarantinePort,
    private val sampler: UsableBytesSampler,
    private val now: () -> Long,
) {
    private val _state = MutableStateFlow(QuarantineUiState())
    val state: StateFlow<QuarantineUiState> = _state.asStateFlow()

    private var held: List<HeldItem> = emptyList()
    private var flow = EmptyQuarantineConfirmation(0)

    suspend fun refresh() {
        held = port.held()
        flow = EmptyQuarantineConfirmation(held.size)
        _state.value = _state.value.copy(card = quarantineCard(held, now()), step = flow.step, warning = flow.warning, typed = "")
    }

    suspend fun restoreAll() {
        _state.value = _state.value.copy(busy = true)
        val restored = port.restoreAll()
        refresh()
        _state.value = _state.value.copy(busy = false, message = "Restored $restored item${if (restored == 1) "" else "s"}")
    }

    fun beginEmpty() = update { flow.begin() }

    fun proceedEmpty() = update { flow.proceed() }

    fun typeCount(text: String) {
        flow.type(text)
        _state.value = _state.value.copy(typed = text)
    }

    fun cancelEmpty() = update { flow.cancel() }

    suspend fun confirmEmpty() {
        if (!flow.confirm()) return
        val expected = held.sumOf { it.sizeBytes }
        _state.value = _state.value.copy(busy = true, step = flow.step, typed = "")
        val before = sampler.usableBytes()
        port.emptyAll(allowPermanent = true)
        val measured = measureFreed(before, sampler.usableBytes(), expected)
        refresh()
        _state.value = _state.value.copy(
            busy = false,
            message = "${formatFreed(measured.freedBytes)} (${formatBytes(measured.expectedBytes)} expected)",
        )
    }

    private inline fun update(block: () -> Unit) {
        block()
        _state.value = _state.value.copy(step = flow.step, warning = flow.warning, typed = if (flow.step == EmptyQuarantineConfirmation.Step.TypeCount) _state.value.typed else "")
    }
}
