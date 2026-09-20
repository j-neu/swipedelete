package com.swipedelete.core.common

import java.io.InputStream
import java.security.MessageDigest

interface Hasher {
    suspend fun sha256(stream: InputStream): String
}

/**
 * Streams [stream] through [MessageDigest] in [CHUNK_SIZE] chunks and never
 * buffers the whole file, which backs the AC-3 quarantine restore guarantee.
 * The stream is read to the end and closed.
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
