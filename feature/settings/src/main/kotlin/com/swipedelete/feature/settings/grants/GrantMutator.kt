package com.swipedelete.feature.settings.grants

import android.content.Intent
import android.net.Uri
import com.swipedelete.core.model.SourceId

/**
 * The write half of the persisted-grant store (SPEC 4.5). Together with
 * `:core:storage`'s `PersistedGrantStore` it is the app's whole grant surface:
 * one implementation (`ContentResolverGrantStore`) implements both, so there is
 * exactly one source of truth for which folders are granted.
 *
 * The core method takes the tree URI as a **string**. That is deliberate: it is
 * what keeps `Uri` behind this port, so `SourceGrantRepository` — the cap,
 * ordering and revoke policy — is constructible and unit-testable without
 * Android. The platform-typed [persist] overload is the one the UI calls with
 * the `Uri` the system picker returned.
 */
interface GrantMutator {

    /**
     * Persists a tree grant. [flags] must be a subset of [FLAG_READ_WRITE]; a
     * provider that offers less is persisted with what it offers and reported
     * through the returned [SourceRef.access].
     */
    suspend fun persist(treeUri: String, flags: Int): PersistResult

    /** The platform-typed entry point: the `Uri` the picker returned. */
    suspend fun persist(uri: Uri, flags: Int): PersistResult = persist(uri.toString(), flags)

    /**
     * Releases the persisted grant for [id]. This only drops the app's access —
     * it never deletes, moves or otherwise touches a file.
     */
    suspend fun revoke(id: SourceId)

    companion object {
        /** `Intent.FLAG_GRANT_READ_URI_PERMISSION`. */
        const val FLAG_READ: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION

        /** `Intent.FLAG_GRANT_WRITE_URI_PERMISSION`. */
        const val FLAG_WRITE: Int = Intent.FLAG_GRANT_WRITE_URI_PERMISSION

        /**
         * What a destination folder needs: a move into it is a copy followed by
         * a delete in the source tree (SPEC 4.5).
         */
        const val FLAG_READ_WRITE: Int = FLAG_READ or FLAG_WRITE
    }
}
