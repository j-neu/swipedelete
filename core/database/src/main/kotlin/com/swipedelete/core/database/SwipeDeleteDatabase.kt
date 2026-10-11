package com.swipedelete.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.swipedelete.core.database.dao.DeckSessionDao
import com.swipedelete.core.database.dao.JournalDao
import com.swipedelete.core.database.dao.MediaItemDao
import com.swipedelete.core.database.dao.SlotDao
import com.swipedelete.core.database.dao.SnapshotDao
import com.swipedelete.core.database.dao.SourceRefDao
import com.swipedelete.core.database.dao.TagDao
import com.swipedelete.core.database.entity.DeckSessionEntity
import com.swipedelete.core.database.entity.JournalEntryEntity
import com.swipedelete.core.database.entity.KeepMarkEntity
import com.swipedelete.core.database.entity.MediaItemEntity
import com.swipedelete.core.database.entity.ProtectedRootEntity
import com.swipedelete.core.database.entity.SlotEntity
import com.swipedelete.core.database.entity.SourceRefEntity
import com.swipedelete.core.database.entity.StorageSnapshotEntity
import com.swipedelete.core.database.entity.TagEntity

@Database(
    entities = [
        JournalEntryEntity::class,
        MediaItemEntity::class,
        SourceRefEntity::class,
        SlotEntity::class,
        DeckSessionEntity::class,
        TagEntity::class,
        KeepMarkEntity::class,
        StorageSnapshotEntity::class,
        ProtectedRootEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class SwipeDeleteDatabase : RoomDatabase() {
    abstract fun journalDao(): JournalDao
    abstract fun mediaItemDao(): MediaItemDao
    abstract fun sourceRefDao(): SourceRefDao
    abstract fun slotDao(): SlotDao
    abstract fun deckSessionDao(): DeckSessionDao
    abstract fun snapshotDao(): SnapshotDao
    abstract fun tagDao(): TagDao

    companion object {
        /**
         * M1-T12: adds `deckSessions.stateData`, the encoded session position
         * that makes a crash/exit resume exact. Nullable, so an existing
         * version-1 row reads back as "no stored position" and the session
         * layer falls back to the persisted cursor.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE deckSessions ADD COLUMN stateData TEXT")
            }
        }
    }
}
