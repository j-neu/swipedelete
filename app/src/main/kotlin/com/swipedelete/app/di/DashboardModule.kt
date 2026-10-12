package com.swipedelete.app.di

import android.content.Context
import android.os.StatFs
import com.swipedelete.core.common.UsableBytesSampler
import com.swipedelete.core.storage.quarantine.EmptyOutcome
import com.swipedelete.core.storage.quarantine.QuarantineAdmin
import com.swipedelete.feature.dashboard.quarantine.HeldItem
import com.swipedelete.feature.dashboard.quarantine.QuarantinePort
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/**
 * Wires the dashboard's ports. `:feature:dashboard` cannot see `:core:storage`'s
 * admin directly, so the adapter lives here; it adds no capability beyond
 * [QuarantineAdmin]'s list, restore-all and gated, journaled Empty.
 */
@Module
@InstallIn(SingletonComponent::class)
object DashboardModule {

    @Provides
    @Singleton
    fun provideQuarantinePort(admin: QuarantineAdmin): QuarantinePort = object : QuarantinePort {
        override suspend fun held(): List<HeldItem> =
            admin.contents().map { HeldItem(sizeBytes = it.sidecar.sizeBytes, expiresAt = it.entry.expiresAt) }

        override suspend fun restoreAll(): Int = admin.restoreAll().restored

        override suspend fun emptyAll(allowPermanent: Boolean): Int = when (val outcome = admin.emptyAll(allowPermanent)) {
            EmptyOutcome.Refused -> 0
            is EmptyOutcome.Emptied -> outcome.count
            is EmptyOutcome.Partial -> outcome.emptied
        }
    }

    /** OS-reported usable bytes of the volume the quarantine lives on (AC-9). */
    @Provides
    @Singleton
    fun provideUsableBytesSampler(@ApplicationContext context: Context): UsableBytesSampler {
        val probe: File = context.getExternalFilesDir(null) ?: context.filesDir
        return UsableBytesSampler { StatFs(probe.absolutePath).availableBytes }
    }
}
