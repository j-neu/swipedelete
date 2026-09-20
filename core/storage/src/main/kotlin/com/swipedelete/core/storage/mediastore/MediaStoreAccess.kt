package com.swipedelete.core.storage.mediastore

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import java.io.InputStream

/**
 * The `ContentResolver` operations the MediaStore gateway needs, behind one
 * seam so the cursor → [com.swipedelete.core.model.MediaItem] mapping and the
 * paging arithmetic are testable on the host with a [android.database.MatrixCursor]
 * (the same seam pattern `DocumentTreeAccess` uses for SAF).
 *
 * [query] takes `offset`/`limit` explicitly rather than leaving paging to the
 * caller-bewitched `sortOrder`: [ContentResolverMediaStoreQuery] appends
 * `LIMIT`/`OFFSET` to the sort clause, which MediaStore's SQLite backend
 * honours, and a test serves an in-memory window instead.
 */
interface MediaStoreAccess {

    fun query(
        uri: Uri,
        projection: Array<String>,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
        offset: Int,
        limit: Int,
    ): Cursor?

    fun openStream(uri: Uri): InputStream

    fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int

    fun update(uri: Uri, values: ContentValues, selection: String?, selectionArgs: Array<String>?): Int

    fun insert(uri: Uri, values: ContentValues): Uri?

    fun registerContentObserver(uri: Uri, observer: ContentObserver)

    fun unregisterContentObserver(observer: ContentObserver)
}

/** The production [MediaStoreAccess] over the app's [ContentResolver]. */
class ContentResolverMediaStoreQuery(private val context: Context) : MediaStoreAccess {

    private val resolver: ContentResolver get() = context.contentResolver

    override fun query(
        uri: Uri,
        projection: Array<String>,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
        offset: Int,
        limit: Int,
    ): Cursor? {
        val ordered = buildString {
            append(sortOrder?.takeIf { it.isNotBlank() } ?: MediaStoreProjection.SORT_ORDER)
            if (limit > 0) {
                append(" LIMIT ").append(limit)
                if (offset > 0) append(" OFFSET ").append(offset)
            }
        }
        return resolver.query(uri, projection, selection, selectionArgs, ordered)
    }

    override fun openStream(uri: Uri): InputStream =
        resolver.openInputStream(uri) ?: error("MediaStore refused to open $uri")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int =
        resolver.delete(uri, selection, selectionArgs)

    override fun update(uri: Uri, values: ContentValues, selection: String?, selectionArgs: Array<String>?): Int =
        resolver.update(uri, values, selection, selectionArgs)

    override fun insert(uri: Uri, values: ContentValues): Uri? =
        resolver.insert(uri, values)

    override fun registerContentObserver(uri: Uri, observer: ContentObserver) {
        resolver.registerContentObserver(uri, true, observer)
    }

    override fun unregisterContentObserver(observer: ContentObserver) {
        resolver.unregisterContentObserver(observer)
    }
}
