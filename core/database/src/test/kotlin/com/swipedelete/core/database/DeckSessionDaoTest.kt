package com.swipedelete.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.swipedelete.core.database.entity.DeckSessionEntity
import com.swipedelete.core.model.DeckStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M1-T12: the session row persists the cursor per decision, plus the encoded
 * session state that makes a resume exact. The DAO writes the two together so a
 * crash can never leave the cursor describing a state that was never saved.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeckSessionDaoTest {

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
    fun `a session round-trips with its encoded state`() = runBlocking {
        db.deckSessionDao().upsert(session("s1", cursor = 0, stateData = "v1;..."))

        val row = db.deckSessionDao().byId("s1")

        assertEquals(0, row?.cursor)
        assertEquals("v1;...", row?.stateData)
        assertEquals(DeckStatus.ACTIVE, row?.status)
    }

    @Test
    fun `persistProgress writes the cursor and the state atomically`() = runBlocking {
        db.deckSessionDao().upsert(session("s1", cursor = 0, stateData = null))

        val updated = db.deckSessionDao().persistProgress("s1", cursor = 3, stateData = "v1;state")

        assertEquals(1, updated)
        val row = db.deckSessionDao().byId("s1")
        assertEquals(3, row?.cursor)
        assertEquals("v1;state", row?.stateData)
    }

    @Test
    fun `persistProgress on an unknown session writes nothing`() = runBlocking {
        assertEquals(0, db.deckSessionDao().persistProgress("missing", 1, "v1"))
    }

    @Test
    fun `markStatus closes the session`() = runBlocking {
        db.deckSessionDao().upsert(session("s1", cursor = 0, stateData = null))

        assertEquals(1, db.deckSessionDao().markStatus("s1", DeckStatus.COMPLETED))
        assertEquals(DeckStatus.COMPLETED, db.deckSessionDao().byId("s1")?.status)
    }

    @Test
    fun `latestActive returns only the newest ACTIVE session`() = runBlocking {
        db.deckSessionDao().upsert(session("old", cursor = 0, stateData = null, createdAt = 100L))
        db.deckSessionDao().upsert(session("done", cursor = 0, stateData = null, createdAt = 300L, status = DeckStatus.COMPLETED))
        db.deckSessionDao().upsert(session("new", cursor = 0, stateData = null, createdAt = 200L))

        assertEquals("new", db.deckSessionDao().latestActive()?.id)
    }

    @Test
    fun `latestActive is null when nothing is active`() = runBlocking {
        db.deckSessionDao().upsert(session("done", cursor = 0, stateData = null, status = DeckStatus.ABANDONED))

        assertNull(db.deckSessionDao().latestActive())
    }

    private fun session(
        id: String,
        cursor: Int,
        stateData: String?,
        createdAt: Long = 1_000L,
        status: DeckStatus = DeckStatus.ACTIVE,
    ) = DeckSessionEntity(
        id = id,
        queryJson = "folder|src",
        createdAt = createdAt,
        cursor = cursor,
        status = status,
        stateData = stateData,
    )
}
