package com.swipedelete.core.storage.export

import com.swipedelete.core.database.dao.JournalDao
import com.swipedelete.core.database.entity.JournalEntryEntity
import java.io.OutputStream

/**
 * AC-18: writes the complete journal, newest first, as JSON or CSV.
 *
 * It streams page by page (never the whole table in memory), applies no filter,
 * and returns the number of rows written. A write failure propagates, so a
 * caller can never report a half-written file as an export.
 *
 * Paging uses offsets, so a row journaled while an export runs can shift a later
 * page; exporting while a delete batch is in flight is not supported.
 */
class JournalExporter(private val journal: JournalDao, private val pageSize: Int = PAGE) {

    suspend fun exportJson(out: OutputStream): Int {
        val writer = out.bufferedWriter(Charsets.UTF_8)
        writer.write("[")
        var count = 0
        forEachRow { row ->
            writer.write(if (count == 0) "\n" else ",\n")
            writer.write(jsonObject(row))
            count++
        }
        writer.write(if (count == 0) "]\n" else "\n]\n")
        writer.flush()
        return count
    }

    suspend fun exportCsv(out: OutputStream): Int {
        val writer = out.bufferedWriter(Charsets.UTF_8)
        writer.write(HEADER.joinToString(",") + "\r\n")
        var count = 0
        forEachRow { row ->
            writer.write(csvFields(row).joinToString(",") { csv(it) } + "\r\n")
            count++
        }
        writer.flush()
        return count
    }

    private suspend fun forEachRow(block: (JournalEntryEntity) -> Unit) {
        var offset = 0
        while (true) {
            val page = journal.page(pageSize, offset)
            page.forEach(block)
            offset += page.size
            if (page.size < pageSize) return
        }
    }

    private fun csvFields(r: JournalEntryEntity): List<String?> = listOf(
        r.id, r.batchId, r.sessionId, r.itemId, r.opKind.name, r.fromUri, r.toUri, r.hash,
        r.sizeBytes.toString(), r.state.name, r.createdAt.toString(), r.expiresAt.toString(), r.error,
    )

    /** Null is an empty unquoted field; an empty string is `""`, so the two stay distinct. */
    private fun csv(value: String?): String = when {
        value == null -> ""
        value.isEmpty() || value.any { it == ',' || it == '"' || it == '\n' || it == '\r' } ->
            "\"" + value.replace("\"", "\"\"") + "\""
        else -> value
    }

    private fun jsonObject(r: JournalEntryEntity): String {
        val values = csvFields(r)
        return HEADER.indices.joinToString(prefix = "  {", postfix = "}", separator = ", ") { i ->
            val raw = values[i]
            val body = when {
                raw == null -> "null"
                HEADER[i] in NUMERIC -> raw
                else -> jsonString(raw)
            }
            "\"${HEADER[i]}\": $body"
        }
    }

    private fun jsonString(s: String): String = buildString {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }

    private companion object {
        const val PAGE = 200
        val HEADER = listOf(
            "id", "batchId", "sessionId", "itemId", "opKind", "fromUri", "toUri", "hash",
            "sizeBytes", "state", "createdAt", "expiresAt", "error",
        )
        val NUMERIC = setOf("sizeBytes", "createdAt", "expiresAt")
    }
}
