package com.swipedelete.core.storage

/**
 * Existence capability a concrete gateway exposes for the strings its own items
 * carry in the journal.
 *
 * Reconciliation must answer one question for an unresolved row: is the file the
 * row names still present? Re-deriving that from the *shape* of the string is
 * wrong. A SAF item's `uri`/`path` is a synthetic display string
 * (`"${treeUri}/${relativePath}"`, see [com.swipedelete.core.storage.saf.SafStorageGateway.itemAt])
 * that is **not** a resolvable document URI, so a `content://`-heuristic probe
 * such as `ContentResolver.openInputStream` reports every SAF file as absent and
 * misclassifies a genuinely aborted quarantine as `unknown`.
 *
 * The concrete gateway is the only layer that can resolve its own identity
 * scheme, so each implementation answers through the same primitive it uses to
 * read the file: `SafStorageGateway` resolves tree-relative paths through its
 * [com.swipedelete.core.storage.saf.DocumentTreeAccess]; `RawStorageGateway`
 * checks [java.io.File]. The `:app` graph wires the reconciler's `exists`
 * lambda to this interface instead of a URI-shape heuristic.
 */
interface ItemLocator {
    /** True when [uri] names a document/path this gateway can currently resolve. */
    suspend fun exists(uri: String): Boolean
}
