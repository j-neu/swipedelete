package com.swipedelete.feature.deck.session

import com.swipedelete.core.model.Category
import com.swipedelete.core.model.ItemId
import com.swipedelete.core.model.SessionId
import com.swipedelete.core.model.SlotId
import com.swipedelete.core.model.SourceId
import com.swipedelete.feature.deck.domain.DeckAction
import com.swipedelete.feature.deck.domain.DeckQuery
import com.swipedelete.feature.deck.domain.DeckState
import com.swipedelete.feature.deck.domain.Deferred
import com.swipedelete.feature.deck.domain.SmartRule
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Encodes a [DeckQuery] into the `deckSessions.queryJson` column and back.
 *
 * Hand-rolled rather than kotlinx-serialization: `:feature:deck` does not apply
 * the serialization compiler plugin (its build file is pre-wired, D5), and the
 * payload is a closed set of four query shapes. Identifiers are URL-safe
 * Base64 so a URI-heavy id can never collide with a delimiter.
 */
internal object DeckQueryCodec {

    fun encode(query: DeckQuery): String = when (query) {
        is DeckQuery.Folder -> "folder|${encodeId(query.source.value)}"
        is DeckQuery.Smart -> "smart|${query.rule.name}"
        is DeckQuery.Module -> "module|${query.category.name}"
        is DeckQuery.Resume -> "resume|${encodeId(query.session.value)}"
    }

    fun decode(text: String): DeckQuery {
        val separator = text.indexOf('|')
        require(separator > 0) { "malformed DeckQuery: $text" }
        val kind = text.substring(0, separator)
        val body = text.substring(separator + 1)
        return when (kind) {
            "folder" -> DeckQuery.Folder(SourceId(decodeId(body)))
            "smart" -> DeckQuery.Smart(SmartRule.valueOf(body))
            "module" -> DeckQuery.Module(Category.valueOf(body))
            "resume" -> DeckQuery.Resume(SessionId(decodeId(body)))
            else -> throw IllegalArgumentException("unknown DeckQuery kind: $kind")
        }
    }
}

/**
 * Encodes a whole [DeckState] into the `deckSessions.stateData` column and back
 * (M1-T12).
 *
 * The session's cursor alone is not enough to resume: the pure reducer compacts
 * the queue and re-queues deferred items, so two sessions can share a cursor
 * while sitting in different places. Persisting the full state — queue, cursor,
 * staging, keeps, the defer ledger and the invertible action history — makes a
 * resume *exact*, including the counts the summary reports.
 *
 * Format (all free-text fields URL-safe Base64, so the `;`, `,` and `~`
 * delimiters are unambiguous):
 *
 * ```
 * v1;<items>;<cursor>;<staged>;<kept>;<deferred>;<decided>;<history>
 * D~<item>~<at>  K~<item>~<at>  M~<item>~<slot>~<at>  F~<item>~<at>~<count>~<requeued>  U~<target>
 * ```
 *
 * [decode] returns `null` for a missing (version-1) or malformed payload so a
 * bad column degrades to a cursor-only resume instead of crashing the launch.
 */
internal object DeckStateCodec {

    private const val VERSION = "v1"
    private const val SECTION_SEPARATOR = ';'
    private const val ENTRY_SEPARATOR = ','
    private const val FIELD_SEPARATOR = '~'

    fun encode(state: DeckState): String = listOf(
        VERSION,
        state.items.joinToString(ENTRY_SEPARATOR.toString()) { encodeId(it.value) },
        state.cursor.toString(),
        state.staged.joinToString(ENTRY_SEPARATOR.toString()) { encodeId(it.value) },
        state.kept.joinToString(ENTRY_SEPARATOR.toString()) { encodeId(it.value) },
        state.deferred.joinToString(ENTRY_SEPARATOR.toString()) { "${encodeId(it.item.value)}$FIELD_SEPARATOR${it.count}" },
        state.decided.joinToString(ENTRY_SEPARATOR.toString()) { encodeId(it.value) },
        state.history.joinToString(ENTRY_SEPARATOR.toString()) { encodeAction(it) },
    ).joinToString(SECTION_SEPARATOR.toString())

    fun decode(text: String?): DeckState? {
        if (text.isNullOrBlank()) return null
        val sections = text.split(SECTION_SEPARATOR)
        if (sections.size != 8 || sections[0] != VERSION) return null
        return runCatching {
            DeckState(
                items = decodeIds(sections[1]).map(::ItemId),
                cursor = sections[2].toInt(),
                staged = decodeIds(sections[3]).map(::ItemId),
                kept = decodeIds(sections[4]).map(::ItemId),
                deferred = decodeDeferred(sections[5]),
                decided = decodeIds(sections[6]).map(::ItemId),
                history = decodeActions(sections[7]),
            )
        }.getOrNull()
    }

    private fun encodeAction(action: DeckAction): String = when (action) {
        is DeckAction.Delete -> listOf("D", encodeId(action.item.value), action.at).joinToString(FIELD_SEPARATOR.toString())
        is DeckAction.Keep -> listOf("K", encodeId(action.item.value), action.at).joinToString(FIELD_SEPARATOR.toString())
        is DeckAction.Move ->
            listOf("M", encodeId(action.item.value), action.slot.value, action.at).joinToString(FIELD_SEPARATOR.toString())

        is DeckAction.Defer -> listOf(
            "F",
            encodeId(action.item.value),
            action.at,
            action.count,
            if (action.requeued) 1 else 0,
        ).joinToString(FIELD_SEPARATOR.toString())

        is DeckAction.Undo -> listOf("U", action.target).joinToString(FIELD_SEPARATOR.toString())
    }

    private fun decodeActions(section: String): List<DeckAction> =
        splitEntries(section).map { entry ->
            val fields = entry.split(FIELD_SEPARATOR)
            when (fields[0]) {
                "D" -> DeckAction.Delete(ItemId(decodeId(fields[1])), fields[2].toInt())
                "K" -> DeckAction.Keep(ItemId(decodeId(fields[1])), fields[2].toInt())
                "M" -> DeckAction.Move(
                    ItemId(decodeId(fields[1])),
                    SlotId(fields[2].toInt()),
                    fields[3].toInt(),
                )

                "F" -> DeckAction.Defer(
                    ItemId(decodeId(fields[1])),
                    fields[2].toInt(),
                    fields[3].toInt(),
                    fields[4] == "1",
                )

                "U" -> DeckAction.Undo(fields[1].toInt())
                else -> error("unknown action tag: ${fields[0]}")
            }
        }

    private fun decodeDeferred(section: String): List<Deferred> =
        splitEntries(section).map { entry ->
            val fields = entry.split(FIELD_SEPARATOR)
            Deferred(ItemId(decodeId(fields[0])), fields[1].toInt())
        }

    private fun decodeIds(section: String): List<String> =
        splitEntries(section).map(::decodeId)

    private fun splitEntries(section: String): List<String> =
        if (section.isEmpty()) emptyList() else section.split(ENTRY_SEPARATOR.toString())
}

private fun encodeId(value: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

private fun decodeId(value: String): String =
    String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
