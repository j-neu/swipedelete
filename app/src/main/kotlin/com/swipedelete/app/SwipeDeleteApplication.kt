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

    override fun onCreate() {
        super.onCreate()
        recoveryCoordinator().start()
    }
}
