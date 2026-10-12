package com.swipedelete.app.di

import android.content.Context
import android.os.StatFs
import com.swipedelete.core.common.UsableBytesSampler
import com.swipedelete.core.storage.RestoreResult
import com.swipedelete.core.storage.quarantine.HistoryStatus as CoreStatus
import com.swipedelete.core.storage.quarantine.EmptyOutcome
import com.swipedelete.core.storage.quarantine.QuarantineAdmin
import com.swipedelete.feature.dashboard.history.HistoryPort
import com.swipedelete.feature.dashboard.history.HistoryRow
import com.swipedelete.feature.dashboard.history.HistoryRowStatus
import com.swipedelete.feature.dashboard.history.RestoreOutcome
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
    fun provideHistoryPort(admin: QuarantineAdmin): HistoryPort = object : HistoryPort {
        override suspend fun rows(): List<HistoryRow> = admin.history().map { item ->
            HistoryRow(
                id = item.row.id,
                batchId = item.row.batchId,
                path = item.row.fromUri,
                sizeBytes = item.row.sizeBytes,
                createdAt = item.row.createdAt,
                status = when (item.status) {
                    CoreStatus.Restorable -> HistoryRowStatus.Restorable
                    CoreStatus.Restored -> HistoryRowStatus.Restored
                    CoreStatus.NoLongerRestorable -> HistoryRowStatus.NoLongerRestorable
                    CoreStatus.Other -> HistoryRowStatus.Other
                },
                kind = item.row.opKind.name.lowercase().replaceFirstChar { it.uppercase() }.replace("_", " "),
            )
        }

        override suspend fun restore(rowId: String, keepBoth: Boolean): RestoreOutcome {
            val item = admin.history().firstOrNull { it.row.id == rowId }
                ?: return RestoreOutcome.Failed("the record no longer exists")
            return when (val result = admin.restore(item, keepBoth)) {
                is RestoreResult.Restored -> RestoreOutcome.Restored
                is RestoreResult.HashMismatch -> RestoreOutcome.Failed("the copy no longer matches its checksum")
                is RestoreResult.Conflict -> when (result.occupiedBy) {
                    "source_not_granted" -> RestoreOutcome.Failed("the folder is no longer granted")
                    "no_quarantine_copy" -> RestoreOutcome.Failed("the quarantine copy is gone")
                    else -> RestoreOutcome.Conflict
                }
            }
        }
    }

    @Provides
    @Singleton
    fun provideUsableBytesSampler(@ApplicationContext context: Context): UsableBytesSampler {
        val probe: File = context.getExternalFilesDir(null) ?: context.filesDir
        return UsableBytesSampler { StatFs(probe.absolutePath).availableBytes }
    }
}
