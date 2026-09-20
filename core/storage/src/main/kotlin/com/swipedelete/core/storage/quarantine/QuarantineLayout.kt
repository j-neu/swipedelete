package com.swipedelete.core.storage.quarantine

import com.swipedelete.core.model.BatchId
import com.swipedelete.core.model.MediaItem
import java.io.File

/**
 * On-disk layout of the quarantine store (V3,
 * `docs/ANDROID-CONSTRAINTS.md`): `\<root\>/\<batchId\>/\<originalRelativePath\>`
 * holds the verified bytes and `\<file\>.swipedelete.json` holds the sidecar
 * beside every quarantined file.
 *
 * Nothing in this object touches the network or the source gateway; it is pure
 * path arithmetic so the layout is unit-testable in isolation.
 */
object QuarantineLayout {

    /** Suffix of the sidecar written next to every quarantined file. */
    const val SIDECAR_SUFFIX: String = ".swipedelete.json"

    /** Suffix of the temporary file a sidecar (or any store file) is staged in. */
    const val TEMP_SUFFIX: String = ".part"

    /** Suffix of the temporary file restore writes beside the target before renaming. */
    const val RESTORE_TEMP_SUFFIX: String = ".swipedelete.restore.part"

    /** The directory holding one quarantine batch. */
    fun batchDir(root: File, batch: BatchId): File = File(root, batch.value)

    /**
     * The file [item] will occupy inside [batch]'s directory. The relative
     * path is sanitised (no `..`, no leading separators) and disambiguated so a
     * name collision can never overwrite an earlier item in the same batch.
     */
    fun target(root: File, batch: BatchId, item: MediaItem): File {
        val relative = sanitize(
            item.relativePath ?: item.path?.substringAfterLast('/') ?: item.id.value,
        )
        return freePath(batchDir(root, batch), relative)
    }

    /** True when [file] is a sidecar written by this store. */
    fun isSidecar(file: File): Boolean = file.isFile && file.name.endsWith(SIDECAR_SUFFIX)

    /** The sidecar beside [file]. */
    fun sidecarFor(file: File): File = File(file.parentFile, file.name + SIDECAR_SUFFIX)

    /** The quarantined data file described by [sidecar]. */
    fun dataFileFor(sidecar: File): File =
        File(sidecar.parentFile, sidecar.name.removeSuffix(SIDECAR_SUFFIX))

    /** The staging file [file] is written to before being renamed into place. */
    fun tempFileFor(file: File): File = File(file.parentFile, file.name + TEMP_SUFFIX)

    /** The staging file restore writes beside [target] before renaming it into place. */
    fun restoreTempFor(target: File): File = File(target.parentFile, target.name + RESTORE_TEMP_SUFFIX)

    /**
     * Normalises a relative path, dropping empty, `.` and `..` segments so a
     * crafted relative path can never escape the batch directory.
     */
    fun sanitize(relative: String): String {
        val segments = relative.replace('\\', '/').split('/')
            .filter { it.isNotBlank() && it != "." && it != ".." }
        return if (segments.isEmpty()) "item" else segments.joinToString("/")
    }

    /** [name] under [directory], disambiguated as `name (1)`, `name (2)`, ... */
    fun freePath(directory: File, name: String): File {
        var candidate = File(directory, name)
        if (!candidate.exists()) return candidate
        // A dotfile (".hidden") has no extension: suffix the whole name so the
        // result stays a dotfile (".hidden (1)") instead of " (1).hidden".
        val dot = name.lastIndexOf('.')
        val hasExtension = dot > 0
        val base = if (hasExtension) name.substring(0, dot) else name
        val extension = if (hasExtension) name.substring(dot) else ""
        var counter = 1
        while (candidate.exists()) {
            candidate = File(directory, "$base ($counter)$extension")
            counter++
        }
        return candidate
    }
}
