package com.swipedelete.app.di

import android.content.Context
import com.swipedelete.app.BuildConfig
import com.swipedelete.app.permissions.AndroidCapabilityController
import com.swipedelete.app.permissions.CapabilityController
import com.swipedelete.app.permissions.TieredFeatureGate
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Wires the capability tier model (M0-T13) into the graph.
 *
 * [TieredFeatureGate] is constructed from `BuildConfig.ALL_FILES_ACCESS`, so the
 * `-PnoAllFilesAccess` contingency build reports All-Files-Access features as
 * [com.swipedelete.app.permissions.Availability.UNAVAILABLE_THIS_BUILD] rather
 * than offering a grant the build cannot honour.
 *
 * The in-context request seams of [AndroidCapabilityController]
 * (`requestMediaPermissions`, `openAllFilesAccessSettings`) are deliberately left
 * at their `null` defaults here: they are Activity-bound launchers, so M1 wires
 * them where the affordance lives rather than making an Application-scoped
 * singleton hold an Activity.
 */
@Module
@InstallIn(SingletonComponent::class)
object CapabilityModule {

    @Provides
    @Singleton
    fun provideCapabilityController(@ApplicationContext context: Context): CapabilityController =
        AndroidCapabilityController(context = context)

    @Provides
    @Singleton
    fun provideTieredFeatureGate(): TieredFeatureGate = TieredFeatureGate(BuildConfig.ALL_FILES_ACCESS)
}
