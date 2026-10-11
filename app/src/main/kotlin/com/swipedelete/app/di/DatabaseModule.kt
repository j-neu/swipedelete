package com.swipedelete.app.di

import android.content.Context
import androidx.room.Room
import com.swipedelete.core.database.SwipeDeleteDatabase
import com.swipedelete.core.database.dao.DeckSessionDao
import com.swipedelete.core.database.dao.JournalDao
import com.swipedelete.core.database.dao.SlotDao
import com.swipedelete.core.database.dao.TagDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The single on-disk database for the process. The journal is the only DAO the
 * M0-T15 graph consumes (write-ahead journal + startup reconciliation); the other
 * DAOs are bound as later M0/M1 tasks need them.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): SwipeDeleteDatabase =
        Room.databaseBuilder(context, SwipeDeleteDatabase::class.java, "swipedelete.db")
            // M1-T12: the session table gained `stateData`; migrate a v1 database
            // instead of destroying it (the journal inside it is AC-1 evidence).
            .addMigrations(SwipeDeleteDatabase.MIGRATION_1_2)
            .build()

    @Provides
    @Singleton
    fun provideJournalDao(database: SwipeDeleteDatabase): JournalDao = database.journalDao()

    /** The five destination slots (SPEC 4.5) live here; M0-T16 owns them. */
    @Provides
    @Singleton
    fun provideSlotDao(database: SwipeDeleteDatabase): SlotDao = database.slotDao()

    /** Slot tags (SPEC 5.3); a TAG-only slot writes here instead of moving the file. */
    @Provides
    @Singleton
    fun provideTagDao(database: SwipeDeleteDatabase): TagDao = database.tagDao()

    /** The durable deck sessions (SPEC 5.5); :feature:deck's SessionController owns them. */
    @Provides
    @Singleton
    fun provideDeckSessionDao(database: SwipeDeleteDatabase): DeckSessionDao = database.deckSessionDao()
}
