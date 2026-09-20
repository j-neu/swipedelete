package com.swipedelete.app

import android.app.Application
import com.swipedelete.app.recovery.RecoveryCoordinator
import com.swipedelete.core.model.RecoveryReport
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/**
 * Process entry point. Its only startup work is publishing the AC-5 recovery
 * report: the graph is Hilt-built, and reconciliation is launched (never awaited)
 * by [RecoveryCoordinator.start].
 */
@HiltAndroidApp
open class SwipeDeleteApplication : Application() {

    @Inject
    lateinit var recovery: RecoveryCoordinator

    /**
     * The coordinator whose report this process publishes.
     *
     * `protected open` is the injection seam the M0-T15 Robolectric test overrides
     * so it can drive `onCreate` with a test dispatcher and a reconciler parked
     * mid-flight; production never overrides it.
     */
    protected open fun recoveryCoordinator(): RecoveryCoordinator = recovery

    /** The published recovery report; `null` until reconciliation completes. */
    val recoveryReport: StateFlow<RecoveryReport?>
        get() = recoveryCoordinator().report

    /**
     * `false` until startup reconciliation has completed.
     *
     * M1's deck must not mutate storage while this is `false`; it must call
     * `RecoveryCoordinator.awaitReady()` (injected) before any destructive action
     * (finding 5). This property exists so an Activity/Compose layer can also gate
     * its affordances on the same signal.
     */
    val recoveryReady: StateFlow<Boolean>
        get() = recoveryCoordinator().ready

    override fun onCreate() {
        super.onCreate()
        recoveryCoordinator().start()
    }
}
