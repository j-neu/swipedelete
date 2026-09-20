package com.swipedelete.core.storage.raw

import java.io.File
import java.io.FileInputStream

/**
 * Thrown when a media item is routed through the Tier-2 raw gateway.
 *
 * This is the AC-10 enforcement signal (spec §4.2 condition 2, §4.6): All Files
 * Access is used only for **non-media** file management, and media always goes
 * through MediaStore. Nothing in the app may catch this and retry elsewhere.
 */
class MediaNotPermittedException(message: String) : RuntimeException(message)

/** How a file came to be classified as media. */
enum class MediaBasis {
    /** The filename carried a known media extension. */
    EXTENSION,

    /** The filename did not, but the leading bytes matched a media signature. */
    CONTENT,

    /**
     * The filename did not carry a media extension and the bytes could not be
     * read. An unreadable file is treated as media: it is not something to
     * enumerate or delete (AC-10's conservative direction).
     */
    UNREADABLE,
}

/** The media verdict for one file, with the [basis] when [media] is true. */
data class MediaVerdict(val media: Boolean, val basis: MediaBasis? = null)

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
 * Classification is **conservative and additive** (§6.4 "a media MIME type or
 * media extension"): a file is media when its name carries a media extension,
 * *or* when its leading bytes match a known media signature. Sniffing can only
 * add media classification — it can never turn a known media file back into a
 * permitted one — because the alternative is a JPEG named `photo` being
 * enumerated and deleted by the one gateway whose existence depends on never
 * touching media.
 *
 * The magic-byte read is bounded ([MAGIC_BYTES]) and is skipped entirely when
 * the extension already says media. A file whose bytes cannot be read is
 * classified as media, never as permitted.
 */
object RawMediaPolicy {

    /** Fallback MIME for a raw file with no recognised media extension. */
    const val DEFAULT_MIME: String = "application/octet-stream"

    /** At most this many leading bytes are read by [looksLikeMedia]. */
    const val MAGIC_BYTES: Int = 512

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
        "jfif" to "image/jpeg",
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
        // Camera raw formats.
        "dng" to "image/x-adobe-dng",
        "raw" to "image/x-raw",
        "cr2" to "image/x-canon-cr2",
        "cr3" to "image/x-canon-cr3",
        "nef" to "image/x-nikon-nef",
        "arw" to "image/x-sony-arw",
        "orf" to "image/x-olympus-orf",
        "rw2" to "image/x-panasonic-rw2",
        "mp4" to "video/mp4",
        "m4v" to "video/x-m4v",
        "mkv" to "video/x-matroska",
        "webm" to "video/webm",
        "avi" to "video/x-msvideo",
        "mov" to "video/quicktime",
        "3gp" to "video/3gpp",
        "3g2" to "video/3gpp2",
        "ogv" to "video/ogg",
        "mts" to "video/mp2t",
        "m2ts" to "video/mp2t",
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

    /**
     * True when [file] carries a media extension. Extension-only; callers that
     * need the content-aware verdict use [classify].
     */
    fun isMediaFile(file: File): Boolean = isMediaName(file.name)

    /**
     * Reads at most [MAGIC_BYTES] leading bytes of [file]. `null` means the
     * bytes could not be read — an unreadable file, not an empty one.
     */
    fun readLeadingBytes(file: File): ByteArray? = try {
        if (!file.isFile) {
            null
        } else {
            FileInputStream(file).use { input ->
                val buffer = ByteArray(MAGIC_BYTES)
                val read = input.read(buffer)
                when {
                    read <= 0 -> ByteArray(0)
                    read == MAGIC_BYTES -> buffer
                    else -> buffer.copyOf(read)
                }
            }
        }
    } catch (error: Throwable) {
        null
    }

    /**
     * True when the leading bytes of [file] match a known media signature.
     * Unreadable bytes are reported as `false` here; [classify] applies the
     * unreadable-is-media rule.
     */
    fun looksLikeMedia(file: File): Boolean = looksLikeMedia(readLeadingBytes(file) ?: return false)

    /** True when [bytes] begin with a known media signature. */
    fun looksLikeMedia(bytes: ByteArray): Boolean =
        isJpeg(bytes) ||
            isPng(bytes) ||
            isGif(bytes) ||
            isRiffMedia(bytes) ||
            hasFtypBrand(bytes) ||
            isMp3(bytes) ||
            isOgg(bytes) ||
            isFlac(bytes) ||
            isMatroska(bytes) ||
            isTiff(bytes)

    /**
     * The AC-10 verdict for [file]: extension first, then a magic-byte sniff,
     * and media when the bytes cannot be read.
     *
     * [readBytes] is the testable seam; it defaults to [readLeadingBytes].
     */
    fun classify(file: File, readBytes: (File) -> ByteArray? = ::readLeadingBytes): MediaVerdict {
        if (isMediaName(file.name)) return MediaVerdict(media = true, basis = MediaBasis.EXTENSION)
        if (!file.exists() || !file.isFile) return MediaVerdict(media = false)
        val bytes = readBytes(file)
        return when {
            bytes == null -> MediaVerdict(media = true, basis = MediaBasis.UNREADABLE)
            looksLikeMedia(bytes) -> MediaVerdict(media = true, basis = MediaBasis.CONTENT)
            else -> MediaVerdict(media = false)
        }
    }

    // --- Magic-byte signatures ---

    private fun isJpeg(bytes: ByteArray): Boolean = bytes.matches(0xFF, 0xD8, 0xFF)

    private fun isPng(bytes: ByteArray): Boolean = bytes.matches(0x89, 0x50, 0x4E, 0x47)

    private fun isGif(bytes: ByteArray): Boolean = bytes.matches(0x47, 0x49, 0x46, 0x38)

    /** `RIFF` + `WEBP` / `AVI ` / `WAVE` at offset 8. */
    private fun isRiffMedia(bytes: ByteArray): Boolean {
        if (!bytes.matches(0x52, 0x49, 0x46, 0x46)) return false
        return bytes.matches(0x57, 0x45, 0x42, 0x50, at = 8) ||
            bytes.matches(0x41, 0x56, 0x49, 0x20, at = 8) ||
            bytes.matches(0x57, 0x41, 0x56, 0x45, at = 8)
    }

    /** `ftyp` at offset 4 — the MP4 / MOV / HEIC family (includes `heic` / `mif1` brands). */
    private fun hasFtypBrand(bytes: ByteArray): Boolean = bytes.matches(0x66, 0x74, 0x79, 0x70, at = 4)

    /** `ID3` or an MPEG audio frame sync (`FF Ex/Fx`). */
    private fun isMp3(bytes: ByteArray): Boolean {
        if (bytes.matches(0x49, 0x44, 0x33)) return true
        if (bytes.size < 2) return false
        val first = bytes[0].toInt() and 0xFF
        val second = bytes[1].toInt() and 0xFF
        return first == 0xFF && (second and 0xE0) == 0xE0 && (second and 0x06) != 0
    }

    private fun isOgg(bytes: ByteArray): Boolean = bytes.matches(0x4F, 0x67, 0x67, 0x53)

    private fun isFlac(bytes: ByteArray): Boolean = bytes.matches(0x66, 0x4C, 0x61, 0x43)

    private fun isMatroska(bytes: ByteArray): Boolean = bytes.matches(0x1A, 0x45, 0xDF, 0xA3)

    /** `II*\0` (little-endian) or `MM\0*` (big-endian). */
    private fun isTiff(bytes: ByteArray): Boolean =
        bytes.matches(0x49, 0x49, 0x2A, 0x00) || bytes.matches(0x4D, 0x4D, 0x00, 0x2A)

    private fun ByteArray.matches(vararg expected: Int, at: Int = 0): Boolean {
        if (size < at + expected.size) return false
        return expected.withIndex().all { (offset, value) -> (this[at + offset].toInt() and 0xFF) == value }
    }
}
