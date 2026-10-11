package com.swipedelete.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.swipedelete.core.database.entity.TagEntity
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
class TagDaoTest {
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
    fun `a tag round-trips and tagging twice keeps one row`() = runBlocking {
        db.tagDao().upsert(TagEntity("item-1", 2, taggedAt = 10L))
        db.tagDao().upsert(TagEntity("item-1", 2, taggedAt = 20L))

        assertEquals(listOf(TagEntity("item-1", 2, taggedAt = 20L)), db.tagDao().forItem("item-1"))
    }

    @Test
    fun `removing a tag leaves the item's other tags`() = runBlocking {
        db.tagDao().upsert(TagEntity("item-1", 2, taggedAt = 10L))
        db.tagDao().upsert(TagEntity("item-1", 3, taggedAt = 10L))

        db.tagDao().delete("item-1", 2)

        assertEquals(listOf(3), db.tagDao().forItem("item-1").map { it.slotId })
        assertTrue(db.tagDao().forItem("item-2").isEmpty())
    }
}
