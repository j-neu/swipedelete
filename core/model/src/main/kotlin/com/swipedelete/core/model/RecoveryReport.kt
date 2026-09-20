package com.swipedelete.core.model

/**
 * The outcome startup reconciliation (AC-5) can assign to an unresolved journal
 * row. It is deliberately **not** [JournalState]: the journal can be `PENDING`,
 * `DONE`, `FAILED`, `ABORTED` or `RESTORED`, but a recovery pass can only ever
 * conclude that an interrupted operation **completed** or was **abandoned**.
 *
 * - [DONE] — the operation's effect is observable (the bytes are at their
 *   destination, or the permanent delete left nothing behind).
 * - [ABORTED] — the operation's effect is not observable and the source is
 *   intact, so the operation never happened.
 *
 * A row that is neither is reported as unknown and left untouched.
 */
enum class JournalOutcome { ABORTED, DONE }

/** One resolved journal row: [entryId] is the row id, [outcome] its verdict. */
data class Resolved(val entryId: String, val outcome: JournalOutcome)

/**
 * The Recovery Report shown to the user after a crash (spec §6.2: "no item is
 * ever in an unknown state without the user being told").
 *
 * [resolved] lists the rows reconciliation classified and recorded; [unknown]
 * lists the rows it could not classify — those are reported **and left
 * exactly as they were**, since the journal is immutable history.
 */
data class RecoveryReport(
    val resolved: List<Resolved>,
    val unknown: List<JournalEntry>,
)
