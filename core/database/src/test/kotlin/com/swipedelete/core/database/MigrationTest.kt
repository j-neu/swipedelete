package com.swipedelete.core.database

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `MIGRATION_1_2` moves a user from M0 to M1 without wiping their data: it adds
 * `deckSessions.stateData`, and a version-1 row must survive with a null position
 * (finding M1-T15 minor).
 *
 * The version-1 table is created with the exact `createSql` from the checked-in
 * `schemas/.../1.json`, then the shipped [SwipeDeleteDatabase.MIGRATION_1_2] runs
 * against it and the result is inspected. (Robolectric + Room 2.8's
 * `MigrationTestHelper` cannot open its own temp database — a driver-name check
 * mismatch — so the migration itself is exercised directly.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val factory = FrameworkSQLiteOpenHelperFactory()
    private var helper: SupportSQLiteOpenHelper? = null

    @AfterTest
    fun tearDown() {
        helper?.close()
        context.deleteDatabase(DATABASE)
    }

    @Test
    fun `migrate 1 to 2 adds a nullable stateData column and keeps the row`() {
        val opened = openVersion1()
        val db = opened.writableDatabase

        assertFalse(
            hasColumn(db, "deckSessions", "stateData"),
            "precondition: a version-1 database has no stateData column",
        )

        SwipeDeleteDatabase.MIGRATION_1_2.migrate(db)

        assertTrue(
            hasColumn(db, "deckSessions", "stateData"),
            "MIGRATION_1_2 must add deckSessions.stateData",
        )
        db.query("SELECT stateData FROM deckSessions WHERE id = 's1'").use { cursor ->
            assertTrue(cursor.moveToFirst(), "the version-1 row must survive the migration")
            assertTrue(cursor.isNull(0), "a migrated row must read back as 'no stored position'")
        }
        db.query("SELECT COUNT(*) FROM deckSessions").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0), "no row may be lost or duplicated")
        }
    }

    private fun openVersion1(): SupportSQLiteOpenHelper {
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(DATABASE)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // Exact createSql of deckSessions at schema version 1.
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `deckSessions` (`id` TEXT NOT NULL, " +
                            "`queryJson` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                            "`cursor` INTEGER NOT NULL, `status` TEXT NOT NULL, PRIMARY KEY(`id`))",
                    )
                    db.execSQL(
                        "INSERT INTO deckSessions (id, queryJson, createdAt, cursor, status) " +
                            "VALUES ('s1', '{\"kind\":\"Folder\"}', 42, 3, 'ACTIVE')",
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        return factory.create(config).also { helper = it }
    }

    private fun hasColumn(db: SupportSQLiteDatabase, table: String, column: String): Boolean =
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            generateSequence { if (cursor.moveToNext()) cursor.getString(nameIndex) else null }
                .any { it == column }
        }

    private companion object {
        const val DATABASE = "migration-test.db"
    }
}
