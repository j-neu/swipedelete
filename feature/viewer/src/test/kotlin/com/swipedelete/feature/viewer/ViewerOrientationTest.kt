package com.swipedelete.feature.viewer

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * SPEC 5.4: the image viewer decodes at full resolution with **EXIF orientation
 * respected**. Orientation lives in the JPEG APP1/Exif segment (tag 0x0112),
 * which the platform's `BitmapFactory` ignores, so the viewer reads it itself
 * and turns it into the rotation/mirror it applies to the decoded bitmap.
 *
 * These are the real bytes of an EXIF-bearing JPEG header: a known-good
 * fixture builder writes the segment the way a camera does, and the assertions
 * are on the production reader — not on the fixture.
 */
class ViewerOrientationTest {

    @Test
    fun `an EXIF orientation 6 JPEG is read as a 90 degree rotation`() {
        val bytes = JpegFixtures.jpeg(orientation = 6)

        val orientation = ExifOrientation.read(ByteArrayInputStream(bytes))

        assertEquals(
            ImageOrientation.ROTATE_90,
            orientation,
            "orientation 6 means the stored image must be rotated 90 degrees clockwise to display",
        )
        assertEquals(90, orientation.rotationDegrees)
        assertFalse(orientation.mirrored, "orientation 6 is a pure rotation, not a mirror")
    }

    @Test
    fun `a JPEG without an EXIF segment is displayed unrotated`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())

        val orientation = ExifOrientation.read(ByteArrayInputStream(bytes))

        assertEquals(ImageOrientation.NORMAL, orientation)
        assertEquals(0, orientation.rotationDegrees)
        assertFalse(orientation.mirrored)
    }

    @Test
    fun `every EXIF orientation value maps to its documented rotation and mirror`() {
        val expected = mapOf(
            1 to (0 to false),
            2 to (0 to true),
            3 to (180 to false),
            4 to (180 to true),
            5 to (90 to true),
            6 to (90 to false),
            7 to (270 to true),
            8 to (270 to false),
        )

        expected.forEach { (exif, transform) ->
            val orientation = ExifOrientation.read(ByteArrayInputStream(JpegFixtures.jpeg(exif)))
            assertEquals(transform.first, orientation.rotationDegrees, "exif=$exif rotation")
            assertEquals(transform.second, orientation.mirrored, "exif=$exif mirror")
        }
    }

    @Test
    fun `an unknown orientation value is treated as normal`() {
        val orientation = ExifOrientation.read(ByteArrayInputStream(JpegFixtures.jpeg(orientation = 99)))

        assertEquals(ImageOrientation.NORMAL, orientation)
    }

    @Test
    fun `a truncated EXIF segment degrades to normal instead of throwing`() {
        // SOI, then an APP1 marker whose declared length runs past the end of the data.
        val truncated = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0xFF.toByte(), 0xE1.toByte(),
            0x00, 0x40, // length 64, but only 4 bytes follow
            0x45, 0x78, 0x69, 0x66,
        )

        val orientation = ExifOrientation.read(ByteArrayInputStream(truncated))

        assertEquals(ImageOrientation.NORMAL, orientation)
    }
}
