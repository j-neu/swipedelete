package com.swipedelete.core.common

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class HashingTest {
    @Test
    fun `sha256 matches the known digest of an empty stream`() = runTest {
        val digest = Sha256Hasher().sha256(ByteArrayInputStream(ByteArray(0)))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", digest)
    }

    @Test
    fun `sha256 matches MessageDigest for a multi-chunk payload`() = runTest {
        val payload = ByteArray(64 * 1024 * 3 + 17) { (it % 251).toByte() }
        val expected = MessageDigest.getInstance("SHA-256").digest(payload).toHex()
        assertEquals(expected, Sha256Hasher().sha256(ByteArrayInputStream(payload)))
    }

    @Test
    fun `sha256 streams in chunks of at most 64 KiB and never reads the whole file at once`() = runTest {
        val payload = ByteArray(64 * 1024 * 4 + 7) { 7 }
        var largestRequestedBuffer = 0
        val trackingStream = object : ByteArrayInputStream(payload) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                largestRequestedBuffer = maxOf(largestRequestedBuffer, length)
                return super.read(buffer, offset, length)
            }
        }

        val digest = Sha256Hasher().sha256(trackingStream)

        assertTrue(largestRequestedBuffer in 1..(64 * 1024), "requested $largestRequestedBuffer bytes")
        assertEquals(MessageDigest.getInstance("SHA-256").digest(payload).toHex(), digest)
    }

    @Test
    fun `hasher contract holds through an interface-typed reference`() = runTest {
        val hasher: Hasher = Sha256Hasher()
        val payload = "swipedelete".toByteArray()

        val expected = MessageDigest.getInstance("SHA-256").digest(payload).toHex()

        assertEquals(expected, hasher.sha256(ByteArrayInputStream(payload)))
    }
}

private fun ByteArray.toHex(): String =
    joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
