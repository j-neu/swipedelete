package com.swipedelete.core.storage.quarantine

import com.swipedelete.core.common.Clock
import com.swipedelete.core.common.Hasher
import com.swipedelete.core.model.BatchId
import com.swipedelete.core.model.ItemId
import com.swipedelete.core.model.JournalEntry
import com.swipedelete.core.model.JournalState
import com.swipedelete.core.model.MediaItem
import com.swipedelete.core.model.OpKind
import com.swipedelete.core.model.SessionId
import com.swipedelete.core.storage.DeleteResult
import com.swipedelete.core.storage.Failure
import com.swipedelete.core.storage.QuarantineResult
import com.swipedelete.core.storage.RestoreResult
import com.swipedelete.core.storage.StorageGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** A quarantined file, its journal entry and its decoded sidecar. */
data class QuarantineEntry(val entry: JournalEntry, val path: String, val sidecar: Sidecar)

/** A per-item quarantine failure that did not corrupt anything. */
class QuarantineException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * AC-3: the app-managed quarantine. A delete never destroys the bytes: they are
 * copied into `\<root\>/\<batchId\>/\<relative\>`, verified against the source
 * SHA-256 and size, and only then is the original removed (V3's fallback order,
 * since the gateway abstraction exposes no raw rename primitive).
 *
 * The store owns four things:
 * - the **layout** and the JSON **sidecar** beside every file,
 * - the cancellable **copy → verify → delete** strategy (AC-15: a cancelled or
 *   failed copy always leaves the original untouched),
 * - the **space pre-flight** (`§6.1`: never silently downgrade),
 * - **restore**, which verifies the recorded hash before doing anything else and
 *   never overwrites an occupied original path (AC-17).
 *
 * The only path that may permanently delete bytes is [empty] with
 * `allowPermanent = true`; expiry is recorded in the sidecar but never deletes
 * anything.
 *
 * @param root the app-external quarantine root (V3: `getExternalFilesDir(null)`).
 * @param gateway the concrete gateway the bytes are read from and the original
 *   is deleted through.
 * @param retentionMillis the restore window recorded in the sidecar and journal.
 */
class QuarantineStore(
    private val root: File,
    private val gateway: StorageGateway,
    private val hasher: Hasher,
    private val clock: Clock,
    private val retentionMillis: Long = DEFAULT_RETENTION_MILLIS,
) {

    /**
     * §6.1: before writing a single byte, compare the batch's total size with
     * the gateway's free space. A shortfall is reported explicitly and nothing
     * is moved.
     */
    suspend fun preflight(items: List<MediaItem>): QuarantineResult.InsufficientSpace? {
        val needed = items.sumOf { it.sizeBytes }
        val free = gateway.capacity().freeBytes
        return if (needed > free) QuarantineResult.InsufficientSpace(needed, free) else null
    }

    /**
     * Copies [item] into [batch]'s quarantine directory, verifies the copy, writes
     * the sidecar and only then deletes the original.
     *
     * @throws QuarantineException when the copy fails verification or the
     *   original could not be deleted. In the verification case the partial
     *   copy is removed and the original is untouched.
     */
    suspend fun store(
        item: MediaItem,
        batch: BatchId,
        sessionId: SessionId? = null,
        cause: String? = null,
        category: String? = null,
    ): JournalEntry {
        val destination = QuarantineLayout.target(root, batch, item)
        val sidecarFile = QuarantineLayout.sidecarFor(destination)
        val now = clock.nowMillis()

        val (written, digestHex) = copyAndVerify(item, destination)

        val entry = JournalEntry(
            id = "q:${batch.value}:${item.id.value}",
            batchId = batch,
            sessionId = sessionId,
            itemId = item.id,
            opKind = OpKind.QUARANTINE,
            fromUri = item.uri,
            toUri = destination.absolutePath,
            hash = digestHex,
            sizeBytes = written,
            state = JournalState.DONE,
            createdAt = now,
            expiresAt = now + retentionMillis,
        )
        val sidecar = Sidecar(
            originalUri = item.uri,
            originalPath = item.path,
            mediaStoreId = item.mediaStoreId,
            sizeBytes = written,
            mime = item.mime,
            dateModified = item.dateModified,
            sha256 = digestHex,
            cause = cause,
            category = category,
            sessionId = sessionId?.value,
            batchId = batch.value,
            sourceId = item.sourceId.value,
            itemId = item.id.value,
            entryId = entry.id,
            createdAt = now,
            expiresAt = entry.expiresAt,
            quarantinedPath = destination.absolutePath,
            relativePath = item.relativePath,
            state = entry.state.name,
        )

        // The sidecar is written before the original is deleted: if the process
        // dies here both copies exist (a duplicate, never a loss).
        try {
            destination.parentFile?.mkdirs()
            sidecarFile.writeText(SidecarCodec.encode(sidecar))
        } catch (cancelled: CancellationException) {
            destination.delete()
            throw cancelled
        } catch (error: Error) {
            destination.delete()
            throw error
        } catch (error: Throwable) {
            destination.delete()
            throw QuarantineException("sidecar_write_failed", error)
        }

        // Delete the original last, and only after a verified copy is in place.
        val deleted = try {
            gateway.deletePermanently(listOf(item))
        } catch (cancelled: CancellationException) {
            // The verified copy and sidecar stay: a cancellation here can only
            // ever duplicate, never lose, the bytes.
            throw cancelled
        } catch (error: Throwable) {
            throw QuarantineException("original_delete_failed", error)
        }
        if (deleted !is DeleteResult.Deleted || deleted.count != 1) {
            // Keep the verified copy: V3 says report failed-with-copy-present.
            throw QuarantineException("original_delete_failed")
        }
        return entry
    }

    /**
     * AC-3 restore. The recorded SHA-256 is checked **before** anything else;
     * only then is the original path checked for occupancy. A mismatch or a
     * conflict touches nothing.
     */
    suspend fun restore(entry: JournalEntry): RestoreResult {
        val quarantinedPath = entry.toUri
        val quarantined = quarantinedPath?.let(::File)
        if (quarantined == null || !quarantined.isFile) {
            return RestoreResult.Conflict(entry.fromUri, quarantinedPath.orEmpty())
        }
        val sidecarFile = QuarantineLayout.sidecarFor(quarantined)
        if (!sidecarFile.isFile) {
            return RestoreResult.Conflict(entry.fromUri, quarantined.absolutePath)
        }
        val sidecar = try {
            SidecarCodec.decode(sidecarFile.readText())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return RestoreResult.Conflict(entry.fromUri, sidecarFile.absolutePath)
        }

        val actual = hasher.sha256(quarantined.inputStream())
        if (actual != sidecar.sha256) {
            return RestoreResult.HashMismatch(sidecar.sha256, actual)
        }

        val targetPath = sidecar.originalPath ?: sidecar.originalUri
        val target = File(targetPath)
        if (target.exists()) {
            return RestoreResult.Conflict(targetPath, target.absolutePath)
        }

        try {
            target.parentFile?.mkdirs()
            quarantined.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (cancelled: CancellationException) {
            target.delete()
            throw cancelled
        } catch (error: Error) {
            target.delete()
            throw error
        } catch (error: Throwable) {
            target.delete()
            throw QuarantineException("restore_write_failed", error)
        }

        quarantined.delete()
        sidecarFile.delete()
        return RestoreResult.Restored(targetPath)
    }

    /** The current quarantined set, read from the sidecars under [root]. */
    fun contents(): Flow<List<QuarantineEntry>> = flow { emit(scan()) }

    /** A one-shot scan of the quarantine root. Missing sidecars are skipped. */
    fun scan(): List<QuarantineEntry> {
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown()
            .filter { QuarantineLayout.isSidecar(it) }
            .mapNotNull { readEntry(it) }
            .sortedBy { it.entry.createdAt }
            .toList()
    }

    /**
     * The only caller-permitted permanent delete. Without `allowPermanent = true`
     * it refuses with an explicit per-item reason and deletes nothing. Expiry
     * never calls this: nothing is purged silently anywhere in the store.
     *
     * On success returns [QuarantineResult.Quarantined]`(batch, emptyList())` —
     * the batch is now empty.
     */
    fun empty(batch: BatchId, allowPermanent: Boolean): QuarantineResult {
        val directory = QuarantineLayout.batchDir(root, batch)
        if (!directory.isDirectory) {
            return if (allowPermanent) {
                QuarantineResult.Quarantined(batch, emptyList())
            } else {
                QuarantineResult.Partial(emptyList(), emptyList())
            }
        }
        // The layout nests the original relative path, so the batch directory
        // must be walked, not listed shallowly.
        val dataFiles = directory.walkTopDown()
            .filter { it.isFile && !QuarantineLayout.isSidecar(it) }
            .toList()

        if (!allowPermanent) {
            val failures = dataFiles.map { data ->
                val itemId = readEntry(QuarantineLayout.sidecarFor(data))?.entry?.itemId
                    ?: ItemId(data.name)
                Failure(itemId, PERMANENT_REFUSED)
            }
            return QuarantineResult.Partial(emptyList(), failures)
        }

        dataFiles.forEach { data ->
            QuarantineLayout.sidecarFor(data).delete()
            data.delete()
        }
        // Orphan sidecars whose data file is already gone.
        directory.walkTopDown().filter { QuarantineLayout.isSidecar(it) }.forEach { it.delete() }
        directory.deleteRecursively()
        return QuarantineResult.Quarantined(batch, emptyList())
    }

    // --- Internals ---

    /**
     * Streams the source into [destination] in [CHUNK_SIZE] chunks, hashing the
     * bytes as they pass, then re-reads the destination and requires both its
     * length and its SHA-256 to match. On any mismatch the partial copy is
     * removed and the original is never touched (AC-15).
     */
    private suspend fun copyAndVerify(item: MediaItem, destination: File): Pair<Long, String> {
        var written = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            destination.parentFile?.mkdirs()
            gateway.openStream(item).use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(CHUNK_SIZE)
                    while (true) {
                        // Bounded chunks plus a cancellation check at every
                        // boundary: a cancelled copy stops before the original
                        // is deleted.
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        written += read
                    }
                    output.flush()
                }
            }
            val sourceHash = digest.digest().toHex()
            val destinationHash = hasher.sha256(destination.inputStream())
            if (destination.length() != written || destinationHash != sourceHash) {
                destination.delete()
                throw QuarantineException(VERIFICATION_FAILED)
            }
            return written to sourceHash
        } catch (cancelled: CancellationException) {
            destination.delete()
            throw cancelled
        } catch (error: QuarantineException) {
            throw error
        } catch (error: Error) {
            destination.delete()
            throw error
        } catch (error: Throwable) {
            destination.delete()
            throw QuarantineException("copy_failed", error)
        }
    }

    private fun readEntry(sidecarFile: File): QuarantineEntry? {
        val sidecar = try {
            SidecarCodec.decode(sidecarFile.readText())
        } catch (error: Throwable) {
            return null
        }
        return QuarantineEntry(
            entry = entryFrom(sidecar),
            path = QuarantineLayout.dataFileFor(sidecarFile).absolutePath,
            sidecar = sidecar,
        )
    }

    private fun entryFrom(sidecar: Sidecar): JournalEntry = JournalEntry(
        id = sidecar.entryId,
        batchId = BatchId(sidecar.batchId),
        sessionId = sidecar.sessionId?.let(::SessionId),
        itemId = ItemId(sidecar.itemId),
        opKind = OpKind.QUARANTINE,
        fromUri = sidecar.originalUri,
        toUri = sidecar.quarantinedPath,
        hash = sidecar.sha256,
        sizeBytes = sidecar.sizeBytes,
        state = runCatching { JournalState.valueOf(sidecar.state) }
            .getOrDefault(JournalState.DONE),
        createdAt = sidecar.createdAt,
        expiresAt = sidecar.expiresAt,
    )

    companion object {
        /** Journal/sidecar retention window when the caller does not supply one. */
        const val DEFAULT_RETENTION_MILLIS: Long = 30L * 24 * 60 * 60 * 1000

        /** 64 KiB copy chunks; the cancellation check runs between chunks. */
        const val CHUNK_SIZE: Int = 64 * 1024

        /** Reason recorded when `empty` is called without the permanent flag. */
        const val PERMANENT_REFUSED: String = "permanent_delete_requires_allow_permanent"

        /** Reason recorded when the copy does not match the source. */
        const val VERIFICATION_FAILED: String = "verification_failed"
    }
}

private fun ByteArray.toHex(): String =
    joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
