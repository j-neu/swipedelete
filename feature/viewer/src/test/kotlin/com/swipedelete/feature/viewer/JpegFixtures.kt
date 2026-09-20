package com.swipedelete.feature.viewer

import java.util.Base64

/**
 * Real JPEG bytes for the viewer tests. The base image is a genuine 4x2
 * baseline JPEG (encoded once, off-line, and embedded below), so `BitmapFactory`
 * decodes real pixels; the EXIF segment is spliced in directly after the SOI
 * marker, which is exactly where a camera puts it. The production reader is
 * therefore exercised against a real segment on a real JPEG, not a stub.
 *
 * The bytes are embedded rather than generated because the Android unit-test
 * compile classpath has no `javax.imageio`/`java.awt` — the JDK JPEG writer is
 * not available to test sources here.
 */
object JpegFixtures {

    const val NO_EXIF = 0

    private const val BASE_JPEG_BASE64 =
        "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0a" +
            "HBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIy" +
            "MjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAARCAACAAQDASIAAhEB" +
            "AxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9" +
            "AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6" +
            "Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ip" +
            "qrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEB" +
            "AQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJB" +
            "UQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RV" +
            "VldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6" +
            "wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwDr/h1oWj+I" +
            "NCluta0qx1K4X7LtlvbdJnG+xtpX5YE/NJLI59WkZjyxJKKKAP/Z"

    private val baseJpeg: ByteArray = Base64.getDecoder().decode(BASE_JPEG_BASE64)

    /** A real JPEG whose APP1/Exif segment declares [orientation], or none when 0. */
    fun jpeg(orientation: Int = NO_EXIF): ByteArray {
        if (orientation == NO_EXIF) return baseJpeg.copyOf()
        val exif = exifSegment(orientation)
        return baseJpeg.copyOfRange(0, 2) + exif + baseJpeg.copyOfRange(2, baseJpeg.size)
    }

    /** APP1(Exif) with a big-endian IFD0 carrying tag 0x0112 = [orientation]. */
    private fun exifSegment(orientation: Int): ByteArray {
        val exifHeader = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0x00, 0x00) // "Exif\0\0"
        val tiff = byteArrayOf(
            0x4D, 0x4D, // "MM" big-endian
            0x00, 0x2A, // 42
            0x00, 0x00, 0x00, 0x08, // IFD0 at offset 8 from the TIFF header
            0x00, 0x01, // one directory entry
            0x01, 0x12, // tag 0x0112 (Orientation)
            0x00, 0x03, // type SHORT
            0x00, 0x00, 0x00, 0x01, // count 1
            (orientation shr 8).toByte(), orientation.toByte(), 0x00, 0x00, // the value
            0x00, 0x00, 0x00, 0x00, // no next IFD
        )
        val payload = exifHeader + tiff
        val length = payload.size + 2
        return byteArrayOf(
            0xFF.toByte(), 0xE1.toByte(), // APP1
            (length shr 8).toByte(), length.toByte(),
        ) + payload
    }
}
