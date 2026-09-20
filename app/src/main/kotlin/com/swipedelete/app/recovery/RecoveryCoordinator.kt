package com.swipedelete.app.recovery

import com.swipedelete.core.common.DispatchersProvider
import com.swipedelete.core.model.RecoveryReport
import com.swipedelete.core.storage.Reconciler
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Runs AC-5 startup reconciliation and publishes its [RecoveryReport].
 *
 * The work is launched, never awaited: [start] returns as soon as the coroutine is
 * scheduled, so `Application.onCreate` cannot be delayed by a slow journal or a
 * large quarantine scan. The report is `null` until reconciliation finishes — the
 * UI shows the recovery summary only once there is something true to show.
 */
@Singleton
class RecoveryCoordinator @Inject constructor(
    private val reconciler: Reconciler,
    private val dispatchers: DispatchersProvider,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)

    private val _report = MutableStateFlow<RecoveryReport?>(null)

    /** The published report; `null` until reconciliation has run. */
    val report: StateFlow<RecoveryReport?> = _report.asStateFlow()

    fun start(): Job = scope.launch {
        _report.value = reconciler.reconcile()
    }
}
