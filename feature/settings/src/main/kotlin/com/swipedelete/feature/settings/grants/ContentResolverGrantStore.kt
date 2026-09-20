package com.swipedelete.feature.settings.grants

import android.content.Context
import android.net.Uri
import com.swipedelete.core.model.SourceId
import com.swipedelete.core.model.SourceRef
import com.swipedelete.core.storage.saf.PersistedGrantStore

/**
 * The **one** persisted-grant store: it implements both the read port
 * (`PersistedGrantStore`, defined by `:core:storage`) and the write port
 * ([GrantMutator]) over the platform's own grant table
 * (`ContentResolver.persistedUriPermissions`). There is deliberately no second
 * grant source anywhere in the app — this class is what `:app`'s Hilt graph
 * binds both interfaces to, so the gateway, the onboarding flow and the grants
 * screen all read and write the same set.
 *
 * The platform is the source of truth on purpose: grants survive process death
 * and are enumerated by `persistedUriPermissions`, so the app never keeps a
 * shadow copy that can disagree with reality.
 */
class ContentResolverGrantStore(context: Context) : PersistedGrantStore, GrantMutator {

    private val context: Context = context.applicationContext

    /** Every persisted grant, oldest first. */
    override suspend fun sources(): List<SourceRef> =
        context.contentResolver.persistedUriPermissions
            .sortedBy { permission -> permission.persistedTime }
            .map { permission ->
                GrantUriMapping.sourceRef(
                    treeUri = permission.uri.toString(),
                    writePermission = permission.isWritePermission,
                    grantedAt = permission.persistedTime,
                )
            }

    /**
     * Converts the picker's transient grant into a persisted one.
     *
     * V2: the call is never trusted. A `SecurityException` means the transient
     * grant had already expired (or was never offered), and the result is then
     * verified against `persistedUriPermissions` before it is reported as
     * [PersistResult.Persisted] — a "success" that the platform did not record
     * would leave a dead slot in the UI.
     */
    override suspend fun persist(treeUri: String, flags: Int): PersistResult {
        val uri = Uri.parse(treeUri)
        val requested = flags and GrantMutator.FLAG_READ_WRITE
        return try {
            context.contentResolver.takePersistableUriPermission(uri, requested)
            val permission = context.contentResolver.persistedUriPermissions
                .firstOrNull { it.uri == uri }
                ?: return PersistResult.GrantDenied
            PersistResult.Persisted(
                GrantUriMapping.sourceRef(
                    treeUri = permission.uri.toString(),
                    writePermission = permission.isWritePermission,
                    grantedAt = permission.persistedTime,
                ),
            )
        } catch (denied: SecurityException) {
            PersistResult.GrantDenied
        } catch (unsupported: UnsupportedOperationException) {
            // A provider that does not offer persistable grants at all.
            PersistResult.GrantDenied
        }
    }

    /**
     * Releases the grant. Idempotent: a grant that is already gone is not an
     * error, and a provider that refuses the release is treated the same way
     * because the target state — no app access — is either reached or already
     * true. No file is ever deleted or moved by a revoke.
     */
    override suspend fun revoke(id: SourceId) {
        val uri = runCatching { Uri.parse(id.value) }.getOrNull() ?: return
        val permission = context.contentResolver.persistedUriPermissions
            .firstOrNull { it.uri == uri } ?: return
        var flags = 0
        if (permission.isReadPermission) flags = flags or GrantMutator.FLAG_READ
        if (permission.isWritePermission) flags = flags or GrantMutator.FLAG_WRITE
        if (flags == 0) return
        runCatching { context.contentResolver.releasePersistableUriPermission(uri, flags) }
    }
}
