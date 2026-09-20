package com.swipedelete.core.storage.fake

import com.swipedelete.core.model.SourceRef

/**
 * Fault-injection hooks consulted by [FakeStorageGateway]. The chaos tests in
 * M0-T10 (Task 19) drive cancellations and I/O failures through this object so
 * the fake can simulate a store that fails at awkward moments.
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
}
