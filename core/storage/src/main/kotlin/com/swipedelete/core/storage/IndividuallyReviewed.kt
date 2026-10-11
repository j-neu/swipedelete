package com.swipedelete.core.storage

import com.swipedelete.core.model.ItemId
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Marks the calling coroutine as acting on [items] the user decided **one at a
 * time, with the file in front of them** — the triage deck (SPEC 5, 6.3).
 *
 * [ProtectedRootsStorageGateway] lets such an item be quarantined or moved even
 * from a protected folder or when it is old: those rules exist to stop a *bulk*
 * flow acting on files the user never looked at (AC-7), and a deck decision is
 * the opposite of that. The exemption is deliberately narrow:
 *
 * - it covers only the listed ids, never the whole call;
 * - the app's own quarantine directory and a granted root stay protected;
 * - permanent deletion is never exempt.
 *
 * Only the deck's session layer may install this element. A cleaner module or
 * any other flow that selects files by rule must not, and
 * `ReviewedExemptionScopeTest` fails the build if one does.
 */
class IndividuallyReviewed(val items: Set<ItemId>) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<IndividuallyReviewed>
}
