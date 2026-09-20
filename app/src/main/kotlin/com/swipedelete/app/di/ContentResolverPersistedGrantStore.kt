package com.swipedelete.app.di

import android.content.Context
import com.swipedelete.core.model.SourceId
import com.swipedelete.core.model.SourceKind
import com.swipedelete.core.model.SourceRef
import com.swipedelete.core.storage.saf.PersistedGrantStore

/**
 * The current source of truth for persisted SAF tree grants: a read-through of
 * `ContentResolver.persistedUriPermissions`, which is exactly the set the user
 * granted and the app may enumerate (SPEC 4.5).
 *
 * Scope note for M0-T16 (Task 25): this binding is intentionally the *raw* grant
 * set. Onboarding, the five destination slots, the 20-grant cap
 * (`GrantCapReached`) and one-tap revoke are not modelled here yet — Task 25
 * layers slot configuration and revocation on top of these [SourceRef]s, most
 * likely over the same `SourceRefDao`. Until then this class is authoritative and
 * `:app`'s graph is the only production consumer.
 *
 * `itemCount` / `totalBytes` are unknown until a tree is walked, so they stay
 * `null` (the deck enumerates lazily rather than at grant time). `grantedAt` is
 * the platform's own `persistedTime`.
 */
class ContentResolverPersistedGrantStore(private val context: Context) : PersistedGrantStore {

    override suspend fun sources(): List<SourceRef> =
        context.contentResolver.persistedUriPermissions.map { permission ->
            val treeUri = permission.uri.toString()
            SourceRef(
                id = SourceId(treeUri),
                treeUri = treeUri,
                displayName = permission.uri.lastPathSegment ?: treeUri,
                access = if (permission.isWritePermission) SourceRef.Access.RW else SourceRef.Access.RO,
                kind = SourceKind.SAF,
                itemCount = null,
                totalBytes = null,
                grantedAt = permission.persistedTime,
            )
        }
}
