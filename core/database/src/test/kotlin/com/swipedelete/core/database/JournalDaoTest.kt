package com.swipedelete.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.swipedelete.core.database.entity.JournalEntryEntity
import com.swipedelete.core.model.JournalState
import com.swipedelete.core.model.OpKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JournalDaoTest {
    private lateinit var db: SwipeDeleteDatabase

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SwipeDeleteDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `pending entries round-trip and are queryable`() = runBlocking {
        db.journalDao().insertPending(pending("j1", "item-1"))
        assertEquals(listOf("j1"), db.journalDao().pending().map { it.id })
    }

    @Test
    fun `markOutcome removes the entry from pending`() = runBlocking {
        db.journalDao().insertPending(pending("j1", "item-1"))
        db.journalDao().markOutcome("j1", JournalState.DONE, null)
        assertTrue(db.journalDao().pending().isEmpty())
    }

    @Test
    fun `rows are never deleted inside the retention window`() = runBlocking {
        db.journalDao().insertPending(pending("j1", "item-1"))
        db.journalDao().markOutcome("j1", JournalState.DONE, null)
        assertEquals(1, db.journalDao().page(limit = 10, offset = 0).size)
    }
}

private const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000

private fun pending(id: String, itemId: String): JournalEntryEntity {
    val createdAt = 1_000L
    return JournalEntryEntity(
        id = id,
        batchId = "batch-1",
        sessionId = null,
        itemId = itemId,
        opKind = OpKind.QUARANTINE,
        fromUri = "content://media/external/images/media/$itemId",
        toUri = null,
        hash = null,
        sizeBytes = 1024L,
        state = JournalState.PENDING,
        createdAt = createdAt,
        expiresAt = createdAt + RETENTION_MS,
        error = null,
    )
}
