package com.swipedelete.core.storage.export

import com.swipedelete.core.database.dao.JournalDao
import com.swipedelete.core.database.entity.JournalEntryEntity
import com.swipedelete.core.model.JournalState
import com.swipedelete.core.model.OpKind
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** AC-18: the export is the whole journal, field for field, never a filtered view. */
class JournalExportParityTest {

    private val rows: List<JournalEntryEntity> = buildList {
        var n = 0
        for (kind in OpKind.entries) for (state in JournalState.entries) {
            n++
            add(
                JournalEntryEntity(
                    id = "id-$n", batchId = "b-${n % 4}", sessionId = if (n % 3 == 0) null else "s-$n",
                    itemId = "item-$n", opKind = kind, fromUri = "content://x/$n", toUri = if (n % 2 == 0) null else "/q/$n",
                    hash = if (n % 5 == 0) null else "h$n", sizeBytes = n * 1000L, state = state,
                    createdAt = 1_000L + n, expiresAt = 9_000L + n, error = if (state == JournalState.FAILED) "boom $n" else null,
                ),
            )
        }
        // Awkward text must survive both formats.
        add(
            JournalEntryEntity(
                id = "odd", batchId = "b", itemId = "i", opKind = OpKind.MOVE,
                fromUri = "Fotos/Caf\u00e9, \"Sommer\"\nzweite Zeile.jpg", toUri = "a,b", hash = null,
                sizeBytes = 0, state = JournalState.FAILED, createdAt = 5_000L, expiresAt = 6_000L,
                error = "line1\r\nline2, \"quoted\" \u2603",
            ),
        )
    }

    private class MemoryJournal(val rows: List<JournalEntryEntity>) : JournalDao {
        var pageCalls = 0
        override suspend fun insertPending(entry: JournalEntryEntity): Long = error("read-only")
        override suspend fun markOutcome(id: String, state: JournalState, error: String?): Int = error("read-only")
        override suspend fun pending() = rows
        override suspend fun unresolved() = rows
        override suspend fun page(limit: Int, offset: Int): List<JournalEntryEntity> {
            pageCalls++
            return rows.sortedByDescending { it.createdAt }.drop(offset).take(limit)
        }
        override suspend fun byBatch(batchId: String) = rows.filter { it.batchId == batchId }
    }

    @Test
    fun `json export parses back to exactly the journal rows`() = runTest {
        val journal = MemoryJournal(rows)
        val out = ByteArrayOutputStream()

        val count = JournalExporter(journal, pageSize = 7).exportJson(out)

        assertEquals(rows.size, count)
        assertEquals(rows.sortedByDescending { it.createdAt }, parseJson(out.toString(Charsets.UTF_8)))
        assert(journal.pageCalls > 2) { "the export must really have paged" }
    }

    @Test
    fun `csv export parses back to exactly the journal rows`() = runTest {
        val out = ByteArrayOutputStream()

        val count = JournalExporter(MemoryJournal(rows), pageSize = 7).exportCsv(out)

        assertEquals(rows.size, count)
        assertEquals(rows.sortedByDescending { it.createdAt }, parseCsv(out.toString(Charsets.UTF_8)))
    }

    @Test
    fun `an empty journal exports a valid empty document`() = runTest {
        val json = ByteArrayOutputStream().also { JournalExporter(MemoryJournal(emptyList())).exportJson(it) }
        val csv = ByteArrayOutputStream().also { JournalExporter(MemoryJournal(emptyList())).exportCsv(it) }

        assertEquals(emptyList(), parseJson(json.toString(Charsets.UTF_8)))
        assertEquals(emptyList(), parseCsv(csv.toString(Charsets.UTF_8)))
    }

    @Test
    fun `a write failure propagates instead of reporting success`() = runTest {
        val broken = object : OutputStream() {
            var written = 0
            override fun write(b: Int) { if (++written > 200) throw IOException("disk full") }
        }

        assertFailsWith<IOException> { JournalExporter(MemoryJournal(rows), pageSize = 7).exportCsv(broken) }
    }

    // --- independent parsers: they share no code with the exporter ---

    private fun parseJson(text: String): List<JournalEntryEntity> =
        (Json.parseToJsonElement(text) as JsonArray).map { el ->
            val o = el as JsonObject
            fun s(k: String) = (o.getValue(k) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
            JournalEntryEntity(
                id = s("id")!!, batchId = s("batchId")!!, sessionId = s("sessionId"), itemId = s("itemId")!!,
                opKind = OpKind.valueOf(s("opKind")!!), fromUri = s("fromUri")!!, toUri = s("toUri"), hash = s("hash"),
                sizeBytes = o.getValue("sizeBytes").jsonPrimitive.content.toLong(), state = JournalState.valueOf(s("state")!!),
                createdAt = o.getValue("createdAt").jsonPrimitive.content.toLong(),
                expiresAt = o.getValue("expiresAt").jsonPrimitive.content.toLong(), error = s("error"),
            )
        }

    /** RFC 4180: quoted fields may hold commas, quotes and newlines; an unquoted empty field is null. */
    private fun parseCsv(text: String): List<JournalEntryEntity> {
        val records = ArrayList<List<String?>>()
        var field = StringBuilder()
        var record = ArrayList<String?>()
        var quoted = false
        var wasQuoted = false
        var i = 0
        fun endField() {
            record += if (field.isEmpty() && !wasQuoted) null else field.toString()
            field = StringBuilder(); wasQuoted = false
        }
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
                quoted && c == '"' -> quoted = false
                quoted -> field.append(c)
                c == '"' -> { quoted = true; wasQuoted = true }
                c == ',' -> endField()
                c == '\r' && text.getOrNull(i + 1) == '\n' -> { endField(); records += record; record = ArrayList(); i++ }
                else -> field.append(c)
            }
            i++
        }
        val body = records.drop(1)
        return body.map { r ->
            JournalEntryEntity(
                id = r[0]!!, batchId = r[1]!!, sessionId = r[2], itemId = r[3]!!, opKind = OpKind.valueOf(r[4]!!),
                fromUri = r[5]!!, toUri = r[6], hash = r[7], sizeBytes = r[8]!!.toLong(), state = JournalState.valueOf(r[9]!!),
                createdAt = r[10]!!.toLong(), expiresAt = r[11]!!.toLong(), error = r[12],
            )
        }
    }
}
