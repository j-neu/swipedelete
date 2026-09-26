package com.swipedelete.feature.deck.domain

import com.swipedelete.core.model.Category
import com.swipedelete.core.model.SessionId
import com.swipedelete.core.model.SourceId

/**
 * What deck to build (§5.1). A deck is an ordered, frozen list of items; the
 * query records *why* those items are in it, so the provenance line can state
 * the rule in plain language.
 */
sealed interface DeckQuery {
    /** Every item in one granted tree, recursively — the default deck. */
    data class Folder(val source: SourceId) : DeckQuery

    /** A stored query, e.g. "Screenshots" or "Videos > 100 MB". */
    data class Smart(val rule: SmartRule) : DeckQuery

    /** The output of an M3 cleaner module. The type is the bridge; M3 supplies the items. */
    data class Module(val category: Category) : DeckQuery

    /** A persisted session position; materialised by the session layer (Task 40). */
    data class Resume(val session: SessionId) : DeckQuery
}

/**
 * The built-in smart decks (§5.1). The threshold-bearing entries carry the
 * spec's example values — `VIDEOS_OVER` 100 MB, `OLDER_THAN` 90 days; the
 * spec's "[OPEN: configurable default]" is about ordering, not thresholds.
 */
enum class SmartRule(val sizeMb: Long = 0L, val days: Int = 0) {
    SCREENSHOTS,
    VIDEOS_OVER(sizeMb = 100),
    OLDER_THAN(days = 90),
    UNREVIEWED,
    LARGEST_FIRST,
    ;

    /** Space decks order largest first; every other smart deck orders oldest first (SPEC 5.1). */
    val ordersLargestFirst: Boolean get() = this == VIDEOS_OVER || this == LARGEST_FIRST
}
