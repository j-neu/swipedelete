package com.swipedelete.core.storage.mediastore

/**
 * The columns this gateway asks MediaStore for.
 *
 * [SPEC_4_6] is the projection named verbatim by spec §4.6. [DECK] is that
 * projection plus `_display_name`, which §4.6 does not list but a deck cannot
 * label an item without — a `MediaItem` has no name field, so without it every
 * card would be anonymous. [projectionFor] is the API-safe projection actually
 * sent to `ContentResolver`: `relative_path`, `is_pending` and
 * `owner_package_name` only exist from API 29, and `is_trashed` only from API
 * 30, so asking for them on API 26 would fail the query rather than degrade.
 */
object MediaStoreProjection {

    /** The spec §4.6 projection, verbatim and in order. */
    val SPEC_4_6: Array<String> = arrayOf(
        "_id",
        "relative_path",
        "_size",
        "date_added",
        "date_modified",
        "mime_type",
        "width",
        "height",
        "duration",
        "bucket_display_name",
        "is_trashed",
        "is_pending",
        "owner_package_name",
    )

    /** The deck projection: §4.6 plus the display name the deck labels items with. */
    val DECK: Array<String> = SPEC_4_6 + "_display_name"

    /**
     * Columns that exist on every supported API (26+), taken by their string
     * value so the API-1 column set is used on API 26 rather than the newer
     * `MediaColumns` names.
     */
    private val BASE: List<String> = listOf(
        "_id",
        "_size",
        "date_added",
        "date_modified",
        "mime_type",
        "width",
        "height",
        "duration",
        "bucket_display_name",
        "_display_name",
    )

    /** The projection actually queried for [sdkInt], never naming an absent column. */
    fun projectionFor(sdkInt: Int): Array<String> {
        val columns = BASE.toMutableList()
        if (sdkInt >= 29) {
            columns += "relative_path"
            columns += "is_pending"
            columns += "owner_package_name"
        }
        if (sdkInt >= 30) columns += "is_trashed"
        return columns.toTypedArray()
    }

    /**
     * The row selection that keeps `IS_PENDING` and `IS_TRASHED` rows out of a
     * deck. The mapper enforces the same rule per row; this only saves the query
     * from returning them, and is null on API 26–28 where neither column exists.
     */
    fun selectionFor(sdkInt: Int): String? = when {
        sdkInt >= 30 ->
            "(is_pending = 0 OR is_pending IS NULL) AND (is_trashed = 0 OR is_trashed IS NULL)"
        sdkInt >= 29 ->
            "(is_pending = 0 OR is_pending IS NULL)"
        else -> null
    }

    /** A deterministic order so repeated enumerations are stable (frozen contract). */
    const val SORT_ORDER: String = "_id ASC"
}
