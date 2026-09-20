package com.swipedelete.feature.settings.grants

import com.swipedelete.core.model.SourceId
import com.swipedelete.core.model.SourceKind
import com.swipedelete.core.model.SourceRef

/**
 * Pure mapping from a persisted SAF tree grant to the model [SourceRef].
 *
 * It is deliberately free of Android APIs (no `Uri`, no `ContentResolver`), so
 * the mapping the grants screen shows is exercised by the host-JVM tests through
 * the same code the production store uses. `ContentResolver`-typed callers only
 * supply `uri.toString()`, `isWritePermission` and `persistedTime`.
 */
internal object GrantUriMapping {

    /**
     * The folder name to show for a tree URI.
     *
     * A tree URI carries its root as a percent-encoded document id —
     * `content://authority/tree/primary%3ADownload` is the `Download` folder —
     * so the common `%3A` / `%2F` escapes are decoded before the name is taken.
     * This is display-only and never used as identity: [SourceRef.id] is the
     * whole tree URI.
     */
    fun displayName(treeUri: String): String {
        val documentId = treeUri
            .substringAfter("/tree/", missingDelimiterValue = "")
            .substringBefore('/')
            .substringBefore('?')
            .replace("%3A", ":", ignoreCase = true)
            .replace("%2F", "/", ignoreCase = true)
        val candidate = documentId.ifEmpty { treeUri.substringAfterLast('/') }
        val name = candidate.substringAfter(':').substringAfterLast('/')
        return name.ifBlank { candidate.ifBlank { treeUri } }
    }

    /** A grant that can write is `RW`; one that cannot is `RO`. */
    fun access(writePermission: Boolean): SourceRef.Access =
        if (writePermission) SourceRef.Access.RW else SourceRef.Access.RO

    fun sourceRef(treeUri: String, writePermission: Boolean, grantedAt: Long): SourceRef = SourceRef(
        id = SourceId(treeUri),
        treeUri = treeUri,
        displayName = displayName(treeUri),
        access = access(writePermission),
        kind = SourceKind.SAF,
        itemCount = null,
        totalBytes = null,
        grantedAt = grantedAt,
    )
}
