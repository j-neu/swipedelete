package com.swipedelete.core.storage.mediastore

import com.swipedelete.core.storage.raw.RawMediaPolicy

/**
 * The single definition of "media" at the **MediaStore enforcement point**
 * (AC-10, spec §4.2 condition 2).
 *
 * [MediaStoreStorageGateway] only ever queries the images / video / audio
 * collections, so every row it sees is media by construction. This guard is the
 * second, per-row line: a `MIME_TYPE` that is not `image/`, `video/` or
 * `audio/` is dropped rather than mapped, so a mis-tagged row in a media
 * collection (or a future non-media column added to a query) can never reach a
 * deck. It deliberately reuses [RawMediaPolicy.isMediaMime] so the app has one
 * media definition, not two that can drift.
 */
object MediaStoreMediaPolicy {

    /** True when [mime] is a media MIME type, by the app-wide definition. */
    fun isMediaMime(mime: String): Boolean = RawMediaPolicy.isMediaMime(mime)
}
