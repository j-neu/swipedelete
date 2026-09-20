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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Runs AC-5 startup reconciliation and publishes its [RecoveryReport].
 *
 * The work is launched, never awaited: [start] returns as soon as the coroutine is
 * scheduled, so `Application.onCreate` cannot be delayed by a slow journal or a
 * large quarantine scan. The report is `null` until reconciliation finishes — the
 * UI shows the recovery summary only once there is something true to show.
 *
 * **Readiness gates mutation.** Task 18's contract is that reconciliation resolves
 * every record *before any UI can mutate storage*. A launched job alone does not
 * enforce that, so the coordinator publishes [ready] and a documented
 * [awaitReady] guard: M1's deck must consult it before any destructive action.
 * [ready] only becomes `true` after [report] is published; if reconciliation
 * throws, it stays `false`, which blocks mutation rather than allowing it against
 * an unresolved journal.
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

    /**
     * `false` until reconciliation has completed and [report] is published.
     *
     * M1's deck must not mutate storage while this is `false`; use [awaitReady]
     * rather than reading it directly, so the destructive action also receives the
     * report it must act on.
     */
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    fun start(): Job = scope.launch {
        _report.value = reconciler.reconcile()
        // Published last, so `ready == true` implies `report != null`.
        _ready.value = true
    }

    /**
     * The mutation guard M1's deck must consult before any destructive action
     * (delete, quarantine, move, restore): suspends until startup reconciliation
     * has completed, then returns the [RecoveryReport] the action must act on.
     *
     * It never returns without a report, so an action cannot start against an
     * unresolved journal. If reconciliation failed, this suspends indefinitely —
     * the safe direction: no destructive action may run.
     */
    suspend fun awaitReady(): RecoveryReport {
        ready.first { it }
        return report.value
            ?: error("recovery is ready but published no report; this must not happen")
    }
}
