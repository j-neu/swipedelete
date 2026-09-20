package com.swipedelete.core.storage.quarantine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The JSON sidecar written beside every quarantined file. It is the durable
 * record of where the bytes came from and what they hashed to, so a restore can
 * verify the bytes before putting them back (AC-3) and can never be fooled by a
 * tampered quarantine copy.
 *
 * The field set is the minimum the task requires: original URI/path, MediaStore
 * id, size, mtime, MIME, SHA-256, cause/category, session id, batch id, source
 * id, the item id, the journal entry id, the creation timestamp and the expiry.
 */
@Serializable
data class Sidecar(
    val originalUri: String,
    val originalPath: String? = null,
    val mediaStoreId: Long? = null,
    val sizeBytes: Long,
    val mime: String,
    val dateModified: Long = 0L,
    val sha256: String,
    val cause: String? = null,
    val category: String? = null,
    val sessionId: String? = null,
    val batchId: String,
    val sourceId: String,
    val itemId: String,
    val entryId: String,
    val createdAt: Long,
    val expiresAt: Long,
    val quarantinedPath: String,
    val relativePath: String? = null,
    val state: String = "DONE",
)

/**
 * kotlinx-serialization codec for [Sidecar]. Unknown keys are ignored so a
 * sidecar written by an older build still decodes after a field is added.
 */
object SidecarCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(sidecar: Sidecar): String = json.encodeToString(Sidecar.serializer(), sidecar)

    fun decode(text: String): Sidecar = json.decodeFromString(Sidecar.serializer(), text)
}
