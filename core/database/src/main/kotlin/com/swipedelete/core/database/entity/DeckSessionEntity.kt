package com.swipedelete.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.swipedelete.core.model.DeckStatus

@Entity(tableName = "deckSessions")
data class DeckSessionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "queryJson") val queryJson: String,
    @ColumnInfo(name = "createdAt") val createdAt: Long,
    @ColumnInfo(name = "cursor") val cursor: Int,
    @ColumnInfo(name = "status") val status: DeckStatus,
    /**
     * The encoded session position (M1-T12): the cursor alone cannot describe a
     * deck whose queue compacts and re-queues as the user decides, so the exact
     * deck state is stored alongside it. Nullable so a version-1 row (written
     * before this column existed) reads back as "no stored position" rather than
     * as a corrupt one.
     */
    @ColumnInfo(name = "stateData") val stateData: String? = null,
)
