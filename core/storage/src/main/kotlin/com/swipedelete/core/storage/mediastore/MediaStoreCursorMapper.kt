package com.swipedelete.core.storage.mediastore

import android.content.ContentUris
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import com.swipedelete.core.model.ItemId
import com.swipedelete.core.model.MediaItem
import com.swipedelete.core.model.SourceRef

/**
 * The one place a MediaStore cursor row becomes a [MediaItem] (spec §4.6).
 *
 * A row is dropped — not mapped — when it is not media (AC-10), when it is
 * `IS_PENDING` (a write in flight is not something to triage), or when it is
 * `IS_TRASHED` (the OS already holds it). Columns are read defensively: the
 * API-safe projection omits columns that do not exist on API 26–28, and a
 * `MatrixCursor` fixture need only carry the columns a case cares about.
 */
object MediaStoreCursorMapper {

    /** `_id` of the row [cursor] points at, or null when it has no id. */
    fun mediaStoreId(cursor: Cursor): Long? = cursor.longOrNull(MediaStore.MediaColumns._ID)

    /** True when the row must not reach a deck (`IS_PENDING` or `IS_TRASHED`). */
    fun isExcluded(cursor: Cursor): Boolean {
        val pending = cursor.intOrNull(MediaStore.MediaColumns.IS_PENDING)
        if (pending != null && pending != 0) return true
        val trashed = cursor.intOrNull(MediaStore.MediaColumns.IS_TRASHED)
        return trashed != null && trashed != 0
    }

    /**
     * Maps the row [cursor] points at, or returns null when it is excluded —
     * non-media (AC-10), pending or trashed — or has no usable identity.
     */
    fun item(cursor: Cursor, source: SourceRef, collectionUri: Uri): MediaItem? {
        val id = mediaStoreId(cursor) ?: return null
        val mime = cursor.stringOrNull(MediaStore.MediaColumns.MIME_TYPE) ?: return null
        if (!MediaStoreMediaPolicy.isMediaMime(mime)) return null
        if (isExcluded(cursor)) return null

        val relative = relativePath(cursor)
        return MediaItem(
            id = ItemId("mediastore:$id"),
            uri = ContentUris.withAppendedId(collectionUri, id).toString(),
            // MediaStore exposes no raw path the app is allowed to use (V4); this
            // is the logical relative path, never a filesystem handle.
            path = relative,
            relativePath = relative,
            mediaStoreId = id,
            mime = mime,
            sizeBytes = cursor.longOrNull(MediaStore.MediaColumns.SIZE) ?: 0L,
            width = cursor.intOrNull(MediaStore.MediaColumns.WIDTH)?.takeIf { it > 0 },
            height = cursor.intOrNull(MediaStore.MediaColumns.HEIGHT)?.takeIf { it > 0 },
            durationMs = cursor.longOrNull(MediaStore.MediaColumns.DURATION)?.takeIf { it > 0 },
            dateAdded = cursor.longOrNull(MediaStore.MediaColumns.DATE_ADDED) ?: 0L,
            dateModified = cursor.longOrNull(MediaStore.MediaColumns.DATE_MODIFIED) ?: 0L,
            bucket = cursor.stringOrNull(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                ?: relative?.substringBeforeLast('/', "")?.substringAfterLast('/')?.ifEmpty { null },
            sourceId = source.id,
            hash = null,
        )
    }

    /** `RELATIVE_PATH` (the folder) joined with `_display_name`, or just the name. */
    private fun relativePath(cursor: Cursor): String? {
        val directory = cursor.stringOrNull(MediaStore.MediaColumns.RELATIVE_PATH)
            ?.trim()
            ?.trim('/')
            ?.takeIf { it.isNotEmpty() }
        val name = cursor.stringOrNull(MediaStore.MediaColumns.DISPLAY_NAME)?.takeIf { it.isNotEmpty() }
        return when {
            directory != null && name != null -> "$directory/$name"
            name != null -> name
            else -> null
        }
    }
}

private fun Cursor.longOrNull(column: String): Long? {
    val index = getColumnIndex(column)
    return if (index < 0 || isNull(index)) null else getLong(index)
}

private fun Cursor.intOrNull(column: String): Int? {
    val index = getColumnIndex(column)
    return if (index < 0 || isNull(index)) null else getInt(index)
}

private fun Cursor.stringOrNull(column: String): String? {
    val index = getColumnIndex(column)
    return if (index < 0 || isNull(index)) null else getString(index)
}
