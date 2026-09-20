package com.swipedelete.app.di

import android.content.Context
import androidx.room.Room
import com.swipedelete.core.database.SwipeDeleteDatabase
import com.swipedelete.core.database.dao.JournalDao
import com.swipedelete.core.database.dao.SlotDao
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
        Room.databaseBuilder(context, SwipeDeleteDatabase::class.java, "swipedelete.db").build()

    @Provides
    @Singleton
    fun provideJournalDao(database: SwipeDeleteDatabase): JournalDao = database.journalDao()

    /** The five destination slots (SPEC 4.5) live here; M0-T16 owns them. */
    @Provides
    @Singleton
    fun provideSlotDao(database: SwipeDeleteDatabase): SlotDao = database.slotDao()
}
