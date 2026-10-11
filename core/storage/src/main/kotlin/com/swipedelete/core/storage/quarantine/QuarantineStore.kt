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
import com.swipedelete.core.model.SourceId
import com.swipedelete.core.storage.DeleteResult
import com.swipedelete.core.storage.Failure
import com.swipedelete.core.storage.OriginalRestoreOutcome
import com.swipedelete.core.storage.OriginalRestoreRequest
import com.swipedelete.core.storage.OriginalRestorer
import com.swipedelete.core.storage.QuarantineResult
import com.swipedelete.core.storage.RestoreResult
import com.swipedelete.core.storage.StorageGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
    ): JournalEntry = withContext(Dispatchers.IO) {
        val destination = QuarantineLayout.target(root, batch, item)
        val sidecarFile = QuarantineLayout.sidecarFor(destination)
        val sidecarTemp = QuarantineLayout.tempFileFor(sidecarFile)
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

        // The sidecar is staged to a temp file and renamed, and written before
        // the original is deleted: if the process dies here both copies exist
        // and the sidecar is fully present or absent, never torn (a torn sidecar
        // would make the data file invisible to scan()/restore).
        try {
            destination.parentFile?.mkdirs()
            sidecarTemp.writeText(SidecarCodec.encode(sidecar))
            replaceAtomically(sidecarTemp, sidecarFile)
        } catch (cancelled: CancellationException) {
            sidecarTemp.delete()
            sidecarFile.delete()
            destination.delete()
            throw cancelled
        } catch (error: Error) {
            sidecarTemp.delete()
            sidecarFile.delete()
            destination.delete()
            throw error
        } catch (error: Throwable) {
            sidecarTemp.delete()
            sidecarFile.delete()
            destination.delete()
            throw QuarantineException(SIDECAR_WRITE_FAILED, error)
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
        entry
    }

    /**
     * AC-3 restore. The recorded SHA-256 is checked **before** anything else;
     * only then is the original path checked for occupancy. A mismatch or a
     * conflict touches nothing.
     */
    suspend fun restore(entry: JournalEntry): RestoreResult = withContext(Dispatchers.IO) {
        val quarantinedPath = entry.toUri
        val quarantined = quarantinedPath?.let(::File)
        if (quarantined == null || !quarantined.isFile) {
            return@withContext RestoreResult.Conflict(entry.fromUri, quarantinedPath.orEmpty())
        }
        val sidecarFile = QuarantineLayout.sidecarFor(quarantined)
        if (!sidecarFile.isFile) {
            return@withContext RestoreResult.Conflict(entry.fromUri, quarantined.absolutePath)
        }
        val sidecar = try {
            SidecarCodec.decode(sidecarFile.readText())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return@withContext RestoreResult.Conflict(entry.fromUri, sidecarFile.absolutePath)
        }

        val actual = hasher.sha256(quarantined.inputStream())
        if (actual != sidecar.sha256) {
            return@withContext RestoreResult.HashMismatch(sidecar.sha256, actual)
        }

        // A raw, absolute path is restored here. An original that is only
        // reachable as a content URI (a folder grant) is handed to the gateway
        // that owns its tree; writing such a URI as a file path would create a
        // bogus file and then drop the last real copy.
        val targetPath = sidecar.originalPath
        if (targetPath.isNullOrBlank() || !File(targetPath).isAbsolute) {
            return@withContext restoreThroughGateway(sidecar, quarantined, sidecarFile)
        }
        val target = File(targetPath)
        if (target.exists()) {
            return@withContext RestoreResult.Conflict(targetPath, target.absolutePath)
        }

        // Crash-safe and race-free: write beside the target, then rename into
        // place. A death mid-write leaves only the temp file, never a partial
        // target that would block every future restore with a Conflict.
        val temp = QuarantineLayout.restoreTempFor(target)
        try {
            target.parentFile?.mkdirs()
            quarantined.inputStream().use { input ->
                FileOutputStream(temp).use { output -> input.copyTo(output) }
            }
            // Re-check immediately before the rename: a file created since the
            // check above must never be overwritten (AC-17).
            if (target.exists() || !promoteIfAbsent(temp, target)) {
                temp.delete()
                return@withContext RestoreResult.Conflict(targetPath, target.absolutePath)
            }
        } catch (cancelled: CancellationException) {
            temp.delete()
            throw cancelled
        } catch (error: Error) {
            temp.delete()
            throw error
        } catch (error: Throwable) {
            temp.delete()
            throw QuarantineException(RESTORE_WRITE_FAILED, error)
        }

        quarantined.delete()
        sidecarFile.delete()
        RestoreResult.Restored(targetPath)
    }

    /**
     * Restores an original that has no raw path. The quarantine copy and its
     * sidecar are removed only after the gateway reports a verified restore; a
     * gateway that cannot do it leaves the documented [NO_ORIGINAL_PATH] refusal.
     */
    private suspend fun restoreThroughGateway(
        sidecar: Sidecar,
        quarantined: File,
        sidecarFile: File,
    ): RestoreResult {
        val original = sidecar.originalPath ?: sidecar.originalUri
        val restorer = gateway as? OriginalRestorer
            ?: return RestoreResult.Conflict(original, NO_ORIGINAL_PATH)
        val request = OriginalRestoreRequest(
            sourceId = SourceId(sidecar.sourceId),
            originalUri = sidecar.originalUri,
            relativePath = sidecar.relativePath,
            mediaStoreId = sidecar.mediaStoreId,
            mime = sidecar.mime,
            sha256 = sidecar.sha256,
            open = { quarantined.inputStream() },
        )
        return when (val outcome = restorer.restoreOriginal(request)) {
            is OriginalRestoreOutcome.Restored -> {
                quarantined.delete()
                sidecarFile.delete()
                RestoreResult.Restored(outcome.uri)
            }
            is OriginalRestoreOutcome.Occupied -> RestoreResult.Conflict(original, outcome.uri)
            is OriginalRestoreOutcome.Unavailable -> RestoreResult.Conflict(original, outcome.reason)
        }
    }

    /** The current quarantined set, read from the sidecars under [root]. */
    fun contents(): Flow<List<QuarantineEntry>> = flow {
        emit(withContext(Dispatchers.IO) { scan() })
    }

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
                // Even an empty/absent batch must produce an explicit refusal,
                // never a reasonless Partial that reads like success.
                QuarantineResult.Partial(
                    emptyList(),
                    listOf(Failure(ItemId(batch.value), PERMANENT_REFUSED)),
                )
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
    private suspend fun copyAndVerify(item: MediaItem, destination: File): Pair<Long, String> =
        withContext(Dispatchers.IO) {
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
                written to sourceHash
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

    /** Atomically moves [source] onto [target], replacing any existing file. */
    private fun replaceAtomically(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (unsupported: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * Atomically moves [source] onto [target] **only if [target] is still
     * absent**, so a file created since the last occupancy check can never be
     * overwritten (AC-17). Returns false when [target] now exists.
     */
    private fun promoteIfAbsent(source: File, target: File): Boolean = try {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        true
    } catch (unsupported: AtomicMoveNotSupportedException) {
        try {
            Files.move(source.toPath(), target.toPath())
            true
        } catch (exists: FileAlreadyExistsException) {
            false
        }
    } catch (exists: FileAlreadyExistsException) {
        false
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

        /** Reason recorded when the sidecar could not be staged and renamed. */
        const val SIDECAR_WRITE_FAILED: String = "sidecar_write_failed"

        /** Reason recorded when the restored bytes could not be placed. */
        const val RESTORE_WRITE_FAILED: String = "restore_write_failed"

        /**
         * Reason recorded when the sidecar carries no raw absolute original
         * path (a MediaStore/SAF-only source); restore refuses rather than
         * writing to a bogus location and dropping the last copy.
         */
        const val NO_ORIGINAL_PATH: String = "no_raw_original_path"
    }
}

private fun ByteArray.toHex(): String =
    joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
