package com.swipedelete.core.storage.mediastore

import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.swipedelete.core.model.SourceId
import com.swipedelete.core.model.SourceKind
import com.swipedelete.core.model.SourceRef

/**
 * The media collections this gateway serves.
 *
 * Spec §4.6 is media-first: the catalogue is `MediaStore` images, video and
 * audio. **`MediaStore.Files` is deliberately absent** — it is the one
 * collection that holds non-media documents, and enumerating it would put the
 * AC-10 enforcement point in exactly the place §4.2 condition 2 forbids. There
 * is no collection here for documents, downloads, APKs or archives.
 */
enum class MediaStoreCollection(val sourceIdSuffix: String, val displayName: String) {
    IMAGES("images", "Images"),
    VIDEO("video", "Video"),
    AUDIO("audio", "Audio"),
}

/**
 * Maps between a MediaStore collection and the [SourceRef] the frozen
 * [com.swipedelete.core.storage.StorageGateway] speaks.
 *
 * A `SourceRef` is the gateway's identity for a collection: its `treeUri` is the
 * collection's content URI and its `kind` is [SourceKind.MEDIASTORE]. The
 * collection URI is resolved per API — `VOLUME_EXTERNAL` from API 29, the
 * legacy `EXTERNAL_CONTENT_URI` below it.
 */
object MediaStoreCollections {

    /** Every collection a MediaStore deck may enumerate, in a stable order. */
    val ALL: List<MediaStoreCollection> = MediaStoreCollection.entries

    /** The content URI of [collection] on [sdkInt]. */
    fun contentUri(collection: MediaStoreCollection, sdkInt: Int): Uri = when (collection) {
        MediaStoreCollection.IMAGES -> imagesUri(sdkInt)
        MediaStoreCollection.VIDEO -> videoUri(sdkInt)
        MediaStoreCollection.AUDIO -> audioUri(sdkInt)
    }

    /** The [SourceRef] naming [collection] on [sdkInt]. */
    fun sourceFor(collection: MediaStoreCollection, sdkInt: Int): SourceRef = SourceRef(
        id = SourceId(sourceIdFor(collection)),
        treeUri = contentUri(collection, sdkInt).toString(),
        displayName = collection.displayName,
        access = SourceRef.Access.RW,
        kind = SourceKind.MEDIASTORE,
        itemCount = null,
        totalBytes = null,
        grantedAt = 0L,
    )

    /** The collection a [SourceRef] names, or null when it names none of them. */
    fun collectionFor(source: SourceRef, sdkInt: Int): MediaStoreCollection? =
        collectionForSourceId(source.id, sdkInt) ?: collectionForUri(source.treeUri, sdkInt)

    /** The collection a source id names (`mediastore:images`), or null. */
    fun collectionForSourceId(sourceId: SourceId, sdkInt: Int): MediaStoreCollection? =
        ALL.firstOrNull { sourceId.value == sourceIdFor(it) }

    /** The collection a content URI names, or null. */
    fun collectionForUri(uri: String, sdkInt: Int): MediaStoreCollection? {
        if (uri.isBlank()) return null
        return ALL.firstOrNull { collection ->
            val base = contentUri(collection, sdkInt).toString()
            uri == base || uri.startsWith("$base/")
        }
    }

    /** The stable [SourceId] value for [collection]. */
    fun sourceIdFor(collection: MediaStoreCollection): String = "mediastore:${collection.sourceIdSuffix}"

    private fun imagesUri(sdkInt: Int): Uri =
        if (sdkInt >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

    private fun videoUri(sdkInt: Int): Uri =
        if (sdkInt >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

    private fun audioUri(sdkInt: Int): Uri =
        if (sdkInt >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
}
