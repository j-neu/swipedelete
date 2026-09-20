package com.swipedelete.core.storage.raw

import java.io.File

/**
 * Thrown when a media item is routed through the Tier-2 raw gateway.
 *
 * This is the AC-10 enforcement signal (spec §4.2 condition 2, §4.6): All Files
 * Access is used only for **non-media** file management, and media always goes
 * through MediaStore. Nothing in the app may catch this and retry elsewhere.
 */
class MediaNotPermittedException(message: String) : RuntimeException(message)

/**
 * The single definition of "media" for the raw gateway — the **AC-10
 * enforcement point**.
 *
 * Spec §4.2 condition 2: "We never use All Files Access to read or edit
 * media." Spec §4.6: for media we do not walk the filesystem, and keeping media
 * on MediaStore is what makes the All Files Access declaration defensible.
 * [RawStorageGateway] therefore calls into this object at *every* entry point
 * (`metadata`, `openStream`, `move`, `quarantine`, `deletePermanently`,
 * `enumerate`/`scan`), so no M0–M4 feature can accidentally route media through
 * raw paths. The same assertion is re-run in M3-T11 and M5-T03.
 *
 * Media is recognised two ways, both required by §6.4 ("a media MIME type **or**
 * media extension"):
 * - a MIME type whose prefix is `image/`, `video/` or `audio/` — the documented
 *   test for "media MIME"; and
 * - a filename extension known to be media, because a raw file on disk has no
 *   authoritative MIME type and a media file mislabelled
 *   `application/octet-stream` must still be refused.
 */
object RawMediaPolicy {

    /** Fallback MIME for a raw file with no recognised media extension. */
    const val DEFAULT_MIME: String = "application/octet-stream"

    /** MIME prefixes that make a file media (§4.6 / AC-10). */
    val MEDIA_PREFIXES: List<String> = listOf("image/", "video/", "audio/")

    /**
     * Extensions that map to a media MIME. Deliberately broad: a false positive
     * only means the raw gateway refuses a file it could not have served
     * anyway, which is the safe direction (AC-10 forbids touching media).
     */
    private val MEDIA_EXTENSIONS: Map<String, String> = mapOf(
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "bmp" to "image/bmp",
        "heic" to "image/heic",
        "heif" to "image/heif",
        "tif" to "image/tiff",
        "tiff" to "image/tiff",
        "svg" to "image/svg+xml",
        "avif" to "image/avif",
        "mp4" to "video/mp4",
        "m4v" to "video/x-m4v",
        "mkv" to "video/x-matroska",
        "webm" to "video/webm",
        "avi" to "video/x-msvideo",
        "mov" to "video/quicktime",
        "3gp" to "video/3gpp",
        "wmv" to "video/x-ms-wmv",
        "flv" to "video/x-flv",
        "mpeg" to "video/mpeg",
        "mpg" to "video/mpeg",
        "ts" to "video/mp2t",
        "mp3" to "audio/mpeg",
        "m4a" to "audio/mp4",
        "aac" to "audio/aac",
        "wav" to "audio/x-wav",
        "flac" to "audio/flac",
        "ogg" to "audio/ogg",
        "oga" to "audio/ogg",
        "opus" to "audio/opus",
        "wma" to "audio/x-ms-wma",
        "amr" to "audio/amr",
        "mid" to "audio/midi",
        "midi" to "audio/midi",
        "aiff" to "audio/aiff",
    )

    /** True when [mime] is a media MIME type (`image/`, `video/`, `audio/`). */
    fun isMediaMime(mime: String): Boolean {
        val normalised = mime.trim().lowercase()
        return MEDIA_PREFIXES.any { normalised.startsWith(it) }
    }

    /** The MIME of a raw file name: a media MIME when the extension is media, else [DEFAULT_MIME]. */
    fun mimeForName(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        return MEDIA_EXTENSIONS[extension] ?: DEFAULT_MIME
    }

    /** The MIME of a raw [File]. */
    fun mimeOf(file: File): String = mimeForName(file.name)

    /** True when [name] carries a media extension. */
    fun isMediaName(name: String): Boolean = isMediaMime(mimeForName(name))

    /** True when [file] carries a media extension. */
    fun isMediaFile(file: File): Boolean = isMediaName(file.name)
}
