package com.swipedelete.app.di

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.DocumentsContract
import com.swipedelete.core.common.UsableBytesSampler
import com.swipedelete.core.database.dao.JournalDao
import com.swipedelete.core.storage.export.JournalExporter
import com.swipedelete.feature.dashboard.history.ExportFormat
import com.swipedelete.feature.dashboard.history.ExportOutcome
import com.swipedelete.core.storage.StorageGateway
import com.swipedelete.core.storage.RestoreResult
import com.swipedelete.core.storage.quarantine.HistoryStatus as CoreStatus
import com.swipedelete.core.storage.quarantine.EmptyOutcome
import com.swipedelete.core.storage.quarantine.QuarantineAdmin
import com.swipedelete.feature.dashboard.history.HistoryPort
import com.swipedelete.feature.dashboard.history.HistoryRow
import com.swipedelete.feature.dashboard.history.HistoryRowStatus
import com.swipedelete.feature.dashboard.history.RestoreOutcome
import com.swipedelete.feature.dashboard.overview.CapacityInfo
import com.swipedelete.feature.dashboard.overview.OverviewItem
import com.swipedelete.feature.dashboard.overview.OverviewPort
import com.swipedelete.feature.dashboard.quarantine.HeldItem
import com.swipedelete.feature.dashboard.quarantine.QuarantinePort
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    fun provideHistoryPort(
        admin: QuarantineAdmin,
        journal: JournalDao,
        @ApplicationContext context: Context,
    ): HistoryPort = object : HistoryPort {
        override suspend fun export(uri: String, format: ExportFormat): ExportOutcome {
            val target = Uri.parse(uri)
            return try {
                val exporter = JournalExporter(journal)
                val rows = withContext(Dispatchers.IO) {
                    val stream = context.contentResolver.openOutputStream(target, "wt")
                        ?: throw java.io.IOException("could not open the document")
                    stream.use {
                        when (format) {
                            ExportFormat.Json -> exporter.exportJson(it)
                            ExportFormat.Csv -> exporter.exportCsv(it)
                        }
                    }
                }
                ExportOutcome.Exported(rows)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                discard(target)
                throw cancelled
            } catch (error: Exception) {
                discard(target)
                ExportOutcome.Failed(error.message ?: error::class.java.simpleName)
            }
        }

        /** A failed export leaves no partial document behind. */
        private fun discard(target: Uri) {
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, target) }
        }


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
    fun provideOverviewPort(gateway: StorageGateway): OverviewPort = object : OverviewPort {
        override suspend fun items(): List<OverviewItem> = withContext(Dispatchers.IO) {
            val found = ArrayList<OverviewItem>()
            gateway.sources().forEach { source ->
                gateway.enumerate(source, null).collect { page ->
                    page.items.forEach { m ->
                        found += OverviewItem(
                            id = m.id.value,
                            name = m.relativePath?.substringAfterLast('/') ?: m.uri.substringAfterLast('/'),
                            folder = m.relativePath?.substringBeforeLast('/', "")?.ifEmpty { null } ?: m.bucket ?: "(top level)",
                            mime = m.mime,
                            sizeBytes = m.sizeBytes,
                        )
                    }
                }
            }
            found
        }

        override suspend fun capacity(): CapacityInfo =
            gateway.capacity().let { CapacityInfo(it.totalBytes, it.freeBytes, it.reclaimableBytes) }
    }

    @Provides
    @Singleton
    fun provideUsableBytesSampler(@ApplicationContext context: Context): UsableBytesSampler {
        val probe: File = context.getExternalFilesDir(null) ?: context.filesDir
        return UsableBytesSampler { StatFs(probe.absolutePath).availableBytes }
    }
}
