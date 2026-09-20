package com.swipedelete.core.storage.fake

import com.swipedelete.core.model.JournalState
import com.swipedelete.core.model.OpKind
import com.swipedelete.core.model.SourceRef
import kotlinx.coroutines.CancellationException

/**
 * One counted primitive in a batch: a gateway operation on [FakeStorageGateway]
 * or a journal commit made through it. [kind] is the [OpKind] the step belongs
 * to (`null` for untagged steps such as a space pre-flight or a stream open);
 * [label] names it for failure messages.
 */
data class Operation(val kind: OpKind?, val label: String)

/**
 * Thrown to simulate abrupt **process death**. It extends [CancellationException]
 * on purpose: `JournalingStorageGateway`, `QuarantineStore` and
 * `QuarantineStorageGateway` all rethrow a `CancellationException` untouched and
 * do no compensating work, so the exception leaves the durable journal, gateway
 * and quarantine exactly as an unconditional process exit would — no
 * `markAll(FAILED)`, no finalisation mark.
 */
class ProcessDeathException(message: String) : CancellationException(message)

/**
 * An injected, **ordinary** failure. The stack treats it like any delegate or
 * journal error (per-item failure, `markAll(FAILED)`, or an aborted call), which
 * is what distinguishes it from [ProcessDeathException].
 */
class InjectedFault(message: String) : Exception(message)

/**
 * Fault-injection hooks consulted by [FakeStorageGateway]. The chaos tests in
 * M0-T10 (Task 19) drive cancellations and I/O failures through this object so
 * the fake can simulate a store that fails at awkward moments.
 *
 * The object is also the shared **operation ledger** for a batch. Tests extend
 * [FakeStorageGateway] to report gateway operations and route journal commits
 * through [beforeCommit], so a single counter covers the whole
 * `journal write → file operation → journal write` sequence. [failOnCall] and
 * [failOnOperation] then fail an exact primitive, and [beforeOperation] lets the
 * chaos harness stop the sequence at an exact primitive.
 */
class FaultInjector {
    /** Invoked immediately before item [index] is moved by quarantine/move. */
    var beforeMove: (suspend (Int) -> Unit)? = null

    /** Invoked at the start of every enumerate collection. */
    var beforeEnumerate: (suspend (SourceRef) -> Unit)? = null

    /** When true, the next enumerate throws, simulating an unreadable source. */
    var failNextEnumerate: Boolean = false

    /** When true, the next move fails without touching anything. */
    var failNextMove: Boolean = false

    /**
     * Invoked immediately before each counted operation with its 1-based
     * [index] and the operation itself. It may throw (the chaos harness throws
     * [ProcessDeathException] once the index passes its stop point) to abandon
     * the sequence with no further code running.
     */
    var beforeOperation: ((index: Int, operation: Operation) -> Unit)? = null

    private var count: Int = 0
    private val countByKind = mutableMapOf<OpKind, Int>()
    private var failAtCall: Int? = null
    private var failAtOperation: Pair<OpKind, Int>? = null
    private var throwCommitFor: JournalState? = null

    /** How many counted operations have started since the last [reset]. */
    fun operationCount(): Int = count

    /**
     * Arms "the [n]-th counted operation (1-based) throws [InjectedFault]" — the
     * delegate fails on that exact primitive.
     */
    fun failOnCall(n: Int) {
        require(n >= 1) { "n must be 1-based and positive, was $n" }
        failAtCall = n
    }

    /**
     * Arms "the [n]-th counted operation tagged with [op] (1-based) throws
     * [InjectedFault]", so a specific step type can fail without knowing how many
     * other primitives precede it.
     */
    fun failOnOperation(op: OpKind, nth: Int) {
        require(nth >= 1) { "nth must be 1-based and positive, was $nth" }
        failAtOperation = op to nth
    }

    /**
     * Arms "the next journal commit into [state] throws [InjectedFault]", so the
     * harness can fail *during* a commit — the write-ahead insert (`PENDING`) or
     * a terminal `DONE`/`FAILED` mark — rather than only during a file operation.
     */
    fun throwOnCommit(state: JournalState) {
        throwCommitFor = state
    }

    /**
     * Disarms every injection point and clears the ledger, returning the object
     * to the inert state it has when constructed. Used by the determinism test to
     * prove an observed failure came from an injected fault and not from ambient
     * state.
     */
    fun reset() {
        beforeMove = null
        beforeEnumerate = null
        failNextEnumerate = false
        failNextMove = false
        beforeOperation = null
        count = 0
        countByKind.clear()
        failAtCall = null
        failAtOperation = null
        throwCommitFor = null
    }

    /**
     * Called by [FakeStorageGateway] immediately before a gateway operation. An
     * armed [InjectedFault] is thrown here, before the operation's effect, so
     * "the delegate processed items 1..n-1 then failed" is exact.
     */
    fun beforeFakeOperation(kind: OpKind?, label: String) {
        counted(Operation(kind, label))
    }

    /**
     * Called by an instrumented journal immediately before a commit of
     * [opKind]/[state]. [throwOnCommit] is honoured first — and consumed, so it
     * fires exactly once — so a failed commit is never counted as an operation
     * that happened, and later commits (for instance reconciliation's) are
     * unaffected.
     */
    fun beforeCommit(opKind: OpKind, state: JournalState) {
        throwCommitFor?.let { armed ->
            if (armed == state) {
                throwCommitFor = null
                throw InjectedFault("injected journal commit failure into $state")
            }
        }
        counted(Operation(opKind, "journal:$state"))
    }

    private fun counted(operation: Operation) {
        count++
        operation.kind?.let { kind -> countByKind[kind] = (countByKind[kind] ?: 0) + 1 }
        beforeOperation?.invoke(count, operation)
        if (failAtCall == count) {
            throw InjectedFault("injected fault on call $count (${operation.label})")
        }
        val (kind, nth) = failAtOperation ?: return
        if (operation.kind == kind && countByKind[kind] == nth) {
            throw InjectedFault("injected fault on ${kind.name} #$nth (${operation.label})")
        }
    }
}
