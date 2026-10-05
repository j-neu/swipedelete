package com.swipedelete.app.permissions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * The one thing the deck route needs from the Hilt graph that is not a
 * `@HiltViewModel` dependency: the [TieredFeatureGate] that decides whether the
 * whole-library affordance is narrowed (SPEC 4.4 / AC-19). Reached through an
 * entry point rather than injecting a gate into the deck view model, so
 * `:feature:deck` never sees the app's capability model.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface CapabilityEntryPoint {
    fun tieredFeatureGate(): TieredFeatureGate
}

@Composable
fun rememberTieredFeatureGate(): TieredFeatureGate {
    val context = LocalContext.current
    return remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            CapabilityEntryPoint::class.java,
        ).tieredFeatureGate()
    }
}
