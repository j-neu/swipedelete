package com.swipedelete.feature.viewer

/**
 * The eight EXIF orientation values (tag 0x0112), expressed as the transform the
 * viewer applies to the decoded bitmap so the stored image is displayed the way
 * the camera intended (SPEC 5.4).
 *
 * The decomposition is the conventional one: rotate clockwise by
 * [rotationDegrees], then mirror horizontally when [mirrored] is set. That pair
 * covers all eight values, and it is the pair `ImageViewer` feeds to
 * `android.graphics.Matrix`.
 */
enum class ImageOrientation(
    val exifValue: Int,
    val rotationDegrees: Int,
    val mirrored: Boolean,
) {
    NORMAL(1, 0, false),
    FLIP_HORIZONTAL(2, 0, true),
    ROTATE_180(3, 180, false),
    FLIP_VERTICAL(4, 180, true),
    TRANSPOSE(5, 90, true),
    ROTATE_90(6, 90, false),
    TRANSVERSE(7, 270, true),
    ROTATE_270(8, 270, false),
    ;

    companion object {
        /** An unrecognised or absent value is the identity, never a failure. */
        fun fromExifValue(value: Int): ImageOrientation =
            entries.firstOrNull { it.exifValue == value } ?: NORMAL
    }
}

/**
 * Reads the EXIF orientation out of an encoded JPEG/WebP stream without pulling
 * in an image-decoding dependency. `BitmapFactory` ignores orientation, so the
 * viewer must read it from the bytes and apply it itself; `ImageDecoder` does
 * apply it, which is why stills go through [BitmapFactory] and a matrix here.
 *
 * The reader is deliberately total: a stream that is not a JPEG, has no Exif
 * segment, is truncated mid-segment or declares a malformed directory yields
 * [ImageOrientation.NORMAL]. Orientation is a display hint — an unreadable hint
 * must never take the viewer down, and the caller still shows the image.
 */
object ExifOrientation {

    private const val SOI = 0xFFD8
    private const val EOI = 0xFFD9
    private const val SOS = 0xFFDA
    private const val APP1 = 0xFFE1

    private const val EXIF_HEADER_SIZE = 6 // "Exif\0\0"
    private const val TIFF_HEADER_SIZE = 8
    private const val IFD_ENTRY_SIZE = 12

    private const val TAG_ORIENTATION = 0x0112
    private const val TYPE_SHORT = 3
    private const val TYPE_LONG = 4

    fun read(stream: java.io.InputStream): ImageOrientation {
        return try {
            readOrNull(stream) ?: ImageOrientation.NORMAL
        } catch (_: java.io.IOException) {
            ImageOrientation.NORMAL
        }
    }

    private fun readOrNull(stream: java.io.InputStream): ImageOrientation? {
        if (readU16(stream) != SOI) return null

        while (true) {
            val marker = readU16(stream)
            if (marker < 0 || marker == EOI || marker == SOS) return null
            // Segment markers are 0xFFxx; anything else means the header is
            // already malformed, so stop rather than wandering through bytes.
            if (marker and 0xFF00 != 0xFF00) return null

            val length = readU16(stream)
            if (length < 2) return null
            val payloadLength = length - 2

            if (marker == APP1) {
                val payload = readFully(stream, payloadLength) ?: return null
                parseApp1(payload)?.let { return it }
            } else if (!skipFully(stream, payloadLength)) {
                return null
            }
        }
    }

    /** Returns the orientation when [payload] is an Exif APP1 segment that carries it. */
    private fun parseApp1(payload: ByteArray): ImageOrientation? {
        if (payload.size < EXIF_HEADER_SIZE + TIFF_HEADER_SIZE) return null
        if (payload[0] != 'E'.code.toByte() || payload[1] != 'x'.code.toByte() ||
            payload[2] != 'i'.code.toByte() || payload[3] != 'f'.code.toByte()
        ) {
            return null
        }

        val tiff = EXIF_HEADER_SIZE
        val littleEndian = when {
            payload[tiff] == 'I'.code.toByte() && payload[tiff + 1] == 'I'.code.toByte() -> true
            payload[tiff] == 'M'.code.toByte() && payload[tiff + 1] == 'M'.code.toByte() -> false
            else -> return null
        }
        val magic = readU16(payload, tiff + 2, littleEndian)
        if (magic != 0x002A) return null

        val ifdOffset = readU32(payload, tiff + 4, littleEndian) ?: return null
        val ifdStart = tiff.toLong() + ifdOffset
        if (ifdStart < 0 || ifdStart + 2 > payload.size) return null

        val entryCount = readU16(payload, ifdStart.toInt(), littleEndian)
        for (index in 0 until entryCount) {
            val entry = ifdStart.toInt() + 2 + index * IFD_ENTRY_SIZE
            if (entry + IFD_ENTRY_SIZE > payload.size) return null

            if (readU16(payload, entry, littleEndian) != TAG_ORIENTATION) continue
            val type = readU16(payload, entry + 2, littleEndian)
            val count = readU32(payload, entry + 4, littleEndian) ?: return null
            if (count < 1) return null

            val value = when (type) {
                TYPE_SHORT -> readU16(payload, entry + 8, littleEndian)
                TYPE_LONG -> readU32(payload, entry + 8, littleEndian)?.toInt() ?: return null
                else -> return null
            }
            return ImageOrientation.fromExifValue(value)
        }
        return null
    }

    // --- Byte-level helpers. `-1` and `null` both mean "ran out of bytes". ---

    private fun readU16(stream: java.io.InputStream): Int {
        val high = stream.read()
        val low = stream.read()
        if (high < 0 || low < 0) return -1
        return (high shl 8) or low
    }

    private fun readU16(bytes: ByteArray, offset: Int, littleEndian: Boolean): Int {
        val a = bytes[offset].toInt() and 0xFF
        val b = bytes[offset + 1].toInt() and 0xFF
        return if (littleEndian) (b shl 8) or a else (a shl 8) or b
    }

    private fun readU32(bytes: ByteArray, offset: Int, littleEndian: Boolean): Long? {
        if (offset + 4 > bytes.size) return null
        val b0 = (bytes[offset].toInt() and 0xFF).toLong()
        val b1 = (bytes[offset + 1].toInt() and 0xFF).toLong()
        val b2 = (bytes[offset + 2].toInt() and 0xFF).toLong()
        val b3 = (bytes[offset + 3].toInt() and 0xFF).toLong()
        return if (littleEndian) {
            (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
        } else {
            (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
        }
    }

    private fun readFully(stream: java.io.InputStream, length: Int): ByteArray? {
        if (length < 0) return null
        val buffer = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = stream.read(buffer, read, length - read)
            if (count < 0) return null
            read += count
        }
        return buffer
    }

    private fun skipFully(stream: java.io.InputStream, length: Int): Boolean {
        var remaining = length.toLong()
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped <= 0) {
                if (stream.read() < 0) return false
                remaining--
            } else {
                remaining -= skipped
            }
        }
        return true
    }
}
