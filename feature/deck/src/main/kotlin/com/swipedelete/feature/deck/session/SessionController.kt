package com.swipedelete.feature.deck.session

import com.swipedelete.core.common.Clock
import com.swipedelete.core.common.formatFreed
import com.swipedelete.core.common.formatReclaimable
import com.swipedelete.core.database.dao.DeckSessionDao
import com.swipedelete.core.database.entity.DeckSessionEntity
import com.swipedelete.core.model.BatchId
import com.swipedelete.core.model.DeckSession
import com.swipedelete.core.model.DeckStatus
import com.swipedelete.core.model.ItemId
import com.swipedelete.core.model.MediaItem
import com.swipedelete.core.model.SessionId
import com.swipedelete.core.model.Slot
import com.swipedelete.core.model.SlotId
import com.swipedelete.core.storage.Destination
import com.swipedelete.core.storage.StorageGateway
import com.swipedelete.feature.deck.domain.DeckAction
import com.swipedelete.feature.deck.domain.DeckBuilder
import com.swipedelete.feature.deck.domain.DeckQuery
import com.swipedelete.feature.deck.domain.DeckReducer
import com.swipedelete.feature.deck.domain.DeckSnapshot
import com.swipedelete.feature.deck.domain.DeckState
import com.swipedelete.feature.deck.domain.Gesture
import com.swipedelete.feature.deck.domain.SlotMoveCoordinator
import com.swipedelete.feature.deck.domain.SlotMoveOutcome
import java.util.UUID

/**
 * D6: the readiness guard in front of every destructive session action.
 *
 * `:feature:deck` cannot depend on `:app`'s `RecoveryCoordinator`, so the
 * session layer takes this seam and the composition root binds it to
 * `RecoveryCoordinator::awaitReady`. Destructive actions call [await] *before*
 * touching storage, so an unresolved journal blocks mutation (the safe
 * direction) rather than allowing it.
 */
fun interface MutationGate {
    suspend fun await()
}

/**
 * The destructive file operations a session performs, implemented at the
 * composition root over the journaled `StorageGateway`. Keeping it an interface
 * keeps [SessionController] plain Kotlin (D5) and makes "did a mutation run?"
 * directly observable in tests.
 */
interface SessionMutations {
    /** Moves [items] into quarantine (AC-3) and returns how many actually moved. */
    suspend fun quarantine(items: List<MediaItem>, batch: BatchId): Int

    /** Restores every quarantined item belonging to [session] and returns the count. */
    suspend fun restoreSession(session: SessionId): Int

    /**
     * Non-null when [item] must not be destroyed because it changed on disk since
     * the deck was built (AC-8 / §9.5). Production re-reads metadata through the
     * gateway; the default is "unchanged", which keeps this a drop-in seam.
     */
    suspend fun staleReason(item: MediaItem): String? = null
}

/** A staged item that was skipped by Apply because it changed on disk (AC-8). */
data class StaleAbort(val item: MediaItem, val reason: String)

/** The outcome of committing the staging strip (SPEC 5.5 / 6.6). */
data class ApplyResult(val quarantined: Int, val aborted: List<StaleAbort>) {
    val applied: Int get() = quarantined
    val hasAborts: Boolean get() = aborted.isNotEmpty()
}

/** The exit prompt shown when the staging strip is non-empty (SPEC 5.5). */
data class ExitPrompt(val staged: Int)

/**
 * The session summary (SPEC 5.5 / 6.5). Counts are the session's decisions, not
 * the momentary queue; [measuredFreedBytes] is an OS-measured delta supplied by
 * the caller (never a sum of file sizes), and [reclaimableBytes] is the bytes
 * held in quarantine — reclaimable, not freed (AC-9).
 */
data class SessionSummary(
    val sessionId: SessionId,
    val reviewed: Int,
    val kept: Int,
    val movedBySlot: Map<SlotId, Int>,
    val staged: Int,
    /**
     * Items whose staged delete has actually run (moved to quarantine). Counted
     * from [DeckState.decided] crossed with the Delete history, so after an Apply
     * they leave [staged] and appear here instead (finding M1-T15-7).
     */
    val quarantined: Int,
    val deferred: Int,
    val measuredFreedBytes: Long,
    val reclaimableBytes: Long,
    val status: DeckStatus,
) {
    /** The only renderer for the reclaimed-so-far figure (AC-9). */
    val reclaimableText: String get() = formatReclaimable(reclaimableBytes)

    /** The measured-freed figure; rendered only when a permanent delete actually ran. */
    val freedText: String get() = formatFreed(measuredFreedBytes)
}

/** Derives the summary counts from the session's invertible action history. */
fun sessionSummary(
    sessionId: SessionId,
    state: DeckState,
    measuredFreedBytes: Long,
    reclaimableBytes: Long,
    status: DeckStatus,
): SessionSummary = SessionSummary(
    sessionId = sessionId,
    reviewed = state.history.size,
    kept = state.history.count { it is DeckAction.Keep },
    movedBySlot = state.history.filterIsInstance<DeckAction.Move>()
        .groupingBy { it.slot }
        .eachCount(),
    staged = state.staged.size,
    // Apply moves staged deletes to `decided`; they are quarantined, not freed
    // (AC-9) and no longer staged. Deriving this from history alone would keep
    // reporting them as staged forever.
    quarantined = state.decided.count { id -> state.history.any { it is DeckAction.Delete && it.item == id } },
    deferred = state.history.count { it is DeckAction.Defer },
    measuredFreedBytes = measuredFreedBytes,
    reclaimableBytes = reclaimableBytes,
    status = status,
)

/**
 * The session model (SPEC 5.5 / M1-T12): owns the deck state and persists the
 * session row on every decision, so a crash or exit mid-triage resumes at the
 * exact position. It is plain Kotlin — no Android, no Hilt (D5) — and every
 * destructive path is gated on [MutationGate] (D6).
 *
 * A *process death* is modelled by discarding this instance and constructing a
 * new one over the same [DeckSessionDao]: [resume] reads the durable row and
 * restores the exact [DeckState].
 */
class SessionController(
    private val sessions: DeckSessionDao,
    private val builder: DeckBuilder,
    private val clock: Clock,
    private val gate: MutationGate,
    private val mutations: SessionMutations,
    private val measuredFreed: suspend () -> Long,
    private val reclaimable: suspend () -> Long,
    private val sessionIds: () -> SessionId = { SessionId("session-${UUID.randomUUID()}") },
    private val batches: () -> BatchId = { BatchId("batch-${UUID.randomUUID()}") },
    /**
     * The concrete gateway a slot MOVE runs through, so a move can be undone by
     * moving the file back (finding M1-T15-5). Null in pure-state tests; the
     * composition root supplies the journaled gateway.
     */
    private val gateway: StorageGateway? = null,
) {

    private val moveOrigins = HashMap<ItemId, MediaItem>()
    private val moveCurrent = HashMap<ItemId, MediaItem>()

    private val undo = UndoStack { action -> undoMove(action) }

    private var session: DeckSession? = null
    private var snapshot: DeckSnapshot? = null
    private var state: DeckState = DeckState()

    fun currentState(): DeckState = state

    fun currentSession(): DeckSession? = session

    /** Builds the deck for [query] and persists a fresh ACTIVE session at its start. */
    suspend fun start(query: DeckQuery): DeckState {
        require(query !is DeckQuery.Resume) {
            "start() cannot materialise a Resume deck; call resume(sessionId) so the session layer resolves it"
        }
        val built = builder.build(query)
        val row = DeckSession(
            id = sessionIds(),
            queryJson = DeckQueryCodec.encode(query),
            createdAt = clock.nowMillis(),
            cursor = 0,
            status = DeckStatus.ACTIVE,
        )
        sessions.upsert(row.toEntity(stateData = null))
        session = row
        snapshot = built
        state = DeckState(items = built.items.map { it.id })
        return state
    }

    /**
     * Restores the session [id] after a crash or exit and continues it. The
     * exact queue position comes from the encoded state; a version-1 row with
     * no state falls back to the persisted cursor.
     */
    suspend fun resume(id: SessionId): DeckState {
        val row = sessions.byId(id.value)
            ?: throw IllegalArgumentException("no session ${id.value}")
        val query = DeckQueryCodec.decode(row.queryJson)
        require(query !is DeckQuery.Resume) {
            "a resume deck must be resolved to its origin before it is stored (session ${id.value})"
        }
        val built = builder.build(query)
        session = row.toModel()
        snapshot = built
        state = DeckStateCodec.decode(row.stateData)
            ?: DeckState(
                items = built.items.map { it.id },
                cursor = row.cursor.coerceIn(0, (built.items.size - 1).coerceAtLeast(0)),
            )
        return state
    }

    /** Applies one gesture and persists the new position immediately. */
    suspend fun decide(gesture: Gesture, slot: SlotId? = null): DeckState {
        val current = requireSession()
        val next = DeckReducer.reduce(state, gesture, slot)
        state = next
        persist(current.id, next)
        return next
    }

    /**
     * "Discard staging (nothing deleted)": every staged delete is reverted, in
     * reverse order, so the items regain their original queue positions. Pure
     * state surgery — no storage is touched.
     */
    suspend fun discardStaging(): DeckState {
        val current = requireSession()
        if (state.staged.isEmpty()) return state
        var next = state
        for (index in next.history.indices.reversed()) {
            if (next.history[index] is DeckAction.Delete) next = undo.revert(next, index)
        }
        state = next
        persist(current.id, next)
        return next
    }

    /** "Keep staged for later": the session stays ACTIVE with its staging intact. */
    suspend fun keepStaged(): DeckState {
        val current = requireSession()
        persist(current.id, state)
        return state
    }

    /**
     * Reverts the history entry at [index] through the real [UndoStack]. A move
     * entry's file operation is undone through [gateway] (finding M1-T15-5).
     */
    suspend fun revert(index: Int): DeckState {
        val current = requireSession()
        val next = undo.revert(state, index)
        state = next
        persist(current.id, next)
        return next
    }

    /** Two-finger tap (SPEC 5.2): revert the newest history entry. */
    suspend fun undoLast(): DeckState {
        val current = requireSession()
        val next = undo.undoLast(state)
        state = next
        persist(current.id, next)
        return next
    }

    /**
     * Drops [item] on [slot] through the gated [SlotMoveCoordinator] and records
     * the origin/current location so [undoLast]/[revert] can move it back. The
     * move itself is a destructive verb and waits on [MutationGate] (D6).
     */
    suspend fun applySlotMove(slot: Slot, item: MediaItem): SlotMoveOutcome {
        val current = requireSession()
        val gw = gateway ?: return SlotMoveOutcome.Failed("move_unavailable")
        val origin = snapshot?.items?.firstOrNull { it.id == item.id } ?: item
        val outcome = SlotMoveCoordinator(gw, gate = gate, nowMillis = { clock.nowMillis() }).apply(slot, item)
        when (outcome) {
            is SlotMoveOutcome.Moved -> {
                moveOrigins[item.id] = origin
                moveCurrent[item.id] = outcome.to
            }
            is SlotMoveOutcome.Renamed -> {
                moveOrigins[item.id] = origin
                moveCurrent[item.id] = outcome.to
            }
            else -> Unit
        }
        if (outcome is SlotMoveOutcome.Moved || outcome is SlotMoveOutcome.Renamed) {
            state = DeckReducer.reduce(state, Gesture.MOVE, slot.id)
            persist(current.id, state)
        }
        return outcome
    }

    /**
     * "Apply": the destructive commit (SPEC 5.5 / 6.6). It waits for
     * [MutationGate] before moving a single byte (D6), re-checks each staged
     * item against the gateway (AC-8) and aborts the ones that changed on disk
     * with a visible reason, then quarantines the rest and clears them from the
     * strip while keeping them in the session history.
     */
    suspend fun applyStaged(): ApplyResult {
        val current = requireSession()
        val items = state.staged.mapNotNull { id -> snapshot?.items?.firstOrNull { it.id == id } }
        if (items.isEmpty()) return ApplyResult(quarantined = 0, aborted = emptyList())
        gate.await()
        val aborted = ArrayList<StaleAbort>()
        val fresh = ArrayList<MediaItem>(items.size)
        items.forEach { item ->
            val reason = mutations.staleReason(item)
            if (reason == null) fresh += item else aborted += StaleAbort(item, reason)
        }
        val moved = if (fresh.isEmpty()) 0 else mutations.quarantine(fresh, batches())
        val applied = fresh.mapTo(mutableSetOf()) { it.id }
        state = state.copy(
            // Aborted items stay staged so they are not silently destroyed.
            staged = state.staged.filterNot { it in applied },
            decided = state.decided + applied,
        )
        persist(current.id, state)
        return ApplyResult(quarantined = moved, aborted = aborted)
    }

    /** Moves a moved item back to the location the deck captured for it. */
    private suspend fun undoMove(action: DeckAction.Move) {
        val gw = gateway ?: return
        val current = moveCurrent[action.item] ?: return
        val origin = moveOrigins[action.item] ?: return
        val originSource = gw.sources().firstOrNull { it.id == origin.sourceId } ?: return
        val originDirectory = origin.relativePath
            ?.substringBeforeLast('/', missingDelimiterValue = "")
            ?.trim('/')
            ?.takeIf { it.isNotEmpty() }
        gate.await()
        gw.move(current, Destination(originSource, originDirectory))
        moveOrigins.remove(action.item)
        moveCurrent.remove(action.item)
    }

    /**
     * The "restore everything from this session" panic button. Restoring is a
     * file operation, so it is gated on readiness too (D6).
     */
    suspend fun restoreEverything(): Int {
        val current = requireSession()
        gate.await()
        return mutations.restoreSession(current.id)
    }

    /** Closes the session when the run ends. */
    suspend fun finish(status: DeckStatus = DeckStatus.COMPLETED) {
        val current = requireSession()
        sessions.markStatus(current.id.value, status)
        session = current.copy(status = status)
    }

    suspend fun summary(): SessionSummary {
        val current = requireSession()
        return sessionSummary(
            sessionId = current.id,
            state = state,
            measuredFreedBytes = measuredFreed(),
            reclaimableBytes = reclaimable(),
            status = current.status,
        )
    }

    /** Non-null when exiting would leave staged items behind (SPEC 5.5). */
    fun exitPrompt(): ExitPrompt? =
        state.staged.takeIf { it.isNotEmpty() }?.let { ExitPrompt(staged = it.size) }

    private suspend fun persist(id: SessionId, state: DeckState) {
        sessions.persistProgress(id.value, state.cursor, DeckStateCodec.encode(state))
    }

    private fun requireSession(): DeckSession =
        session ?: error("start() or resume() must be called before a session action")

    private fun DeckSession.toEntity(stateData: String?): DeckSessionEntity = DeckSessionEntity(
        id = id.value,
        queryJson = queryJson,
        createdAt = createdAt,
        cursor = cursor,
        status = status,
        stateData = stateData,
    )

    private fun DeckSessionEntity.toModel(): DeckSession =
        DeckSession(SessionId(id), queryJson, createdAt, cursor, status)
}
