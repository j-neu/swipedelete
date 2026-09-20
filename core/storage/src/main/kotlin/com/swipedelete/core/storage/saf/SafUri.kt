package com.swipedelete.core.storage.saf

import java.net.URLDecoder

/**
 * Pure string helpers for SAF tree / document identity.
 *
 * These deliberately avoid `android.net.Uri` and `DocumentsContract` so the
 * package-name and URI-shape rules stay host-testable; only
 * [SafDocumentsContractAccess] may build real provider URIs. A SAF tree URI has
 * the shape `content://authority/tree/<treeDocumentId>` and a document reached
 * through it has the shape
 * `content://authority/tree/<treeDocumentId>/document/<documentId>`.
 *
 * The gateway addresses items by a *tree-relative path* (`"dir/a.gif"`) rather
 * than by opaque document id: that is the convention the rest of the storage
 * module already uses for `MediaItem.uri` / `MediaItem.path`
 * (`"${source.treeUri}/${relativePath}"`), it is what the user sees, and a SAF
 * provider guarantees display names are unique within a parent. [treeDocumentId]
 * recovers the root document id from a tree URI without touching the platform.
 */
object SafUri {

    private const val TREE_SEGMENT = "/tree/"

    /** The root document id encoded in a tree URI (`primary:Folder`), or `""`. */
    fun treeDocumentId(treeUri: String): String {
        val marker = treeUri.indexOf(TREE_SEGMENT)
        if (marker < 0) return ""
        val raw = treeUri.substring(marker + TREE_SEGMENT.length)
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
        return decode(raw)
    }

    /** `"tree://root" + "a/b.gif" -> "tree://root/a/b.gif"`. */
    fun join(treeUri: String, relativePath: String): String {
        val base = treeUri.trimEnd('/')
        val relative = relativePath.trim('/')
        return if (relative.isEmpty()) base else "$base/$relative"
    }

    /** The inverse of [join]: the tree-relative path of [uri] under [treeUri]. */
    fun relativePath(treeUri: String, uri: String): String {
        val base = treeUri.trimEnd('/')
        val stripped = when {
            uri == base -> ""
            uri.startsWith("$base/") -> uri.substring(base.length + 1)
            else -> uri
        }
        return stripped.trim('/')
    }

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, Charsets.UTF_8.name()) }.getOrDefault(value)
}
