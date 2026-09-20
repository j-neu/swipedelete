package com.swipedelete.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.swipedelete.core.model.JournalState
import com.swipedelete.core.model.OpKind

/**
 * Write-ahead journal row. A `PENDING` row is committed before a mutating
 * filesystem operation and moved to a terminal [state] afterwards (AC-1).
 *
 * [expiresAt] is stored (`createdAt + retention`); the caller computes it — this
 * layer never applies retention and exposes no delete: rows within the
 * retention window are immutable history.
 */
@Entity(tableName = "journal")
data class JournalEntryEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "batchId") val batchId: String,
    @ColumnInfo(name = "sessionId") val sessionId: String? = null,
    @ColumnInfo(name = "itemId") val itemId: String,
    @ColumnInfo(name = "opKind") val opKind: OpKind,
    @ColumnInfo(name = "fromUri") val fromUri: String,
    @ColumnInfo(name = "toUri") val toUri: String? = null,
    @ColumnInfo(name = "hash") val hash: String? = null,
    @ColumnInfo(name = "sizeBytes") val sizeBytes: Long,
    @ColumnInfo(name = "state") val state: JournalState,
    @ColumnInfo(name = "createdAt") val createdAt: Long,
    @ColumnInfo(name = "expiresAt") val expiresAt: Long,
    @ColumnInfo(name = "error") val error: String? = null,
)
