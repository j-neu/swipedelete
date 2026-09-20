package com.swipedelete.core.common

import java.io.InputStream
import java.security.MessageDigest

/**
 * Computes the SHA-256 digest of a stream.
 *
 * Ownership contract: the caller transfers [stream] to the implementation, which
 * reads it to the end and closes it. Callers must not reuse the stream after the
 * call; to keep the stream open, pass a non-closing wrapper such as
 * `CloseShieldInputStream`.
 */
interface Hasher {
    suspend fun sha256(stream: InputStream): String
}

/**
 * Streams [stream] through [MessageDigest] in [CHUNK_SIZE] chunks and never
 * buffers the whole file, which backs the AC-3 quarantine restore guarantee.
 * The stream is read to the end and closed (see [Hasher] for the ownership
 * contract).
 */
class Sha256Hasher : Hasher {
    override suspend fun sha256(stream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(CHUNK_SIZE)
        stream.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    companion object {
        const val CHUNK_SIZE: Int = 64 * 1024
    }
}

private fun ByteArray.toHex(): String =
    joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
