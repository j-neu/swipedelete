package com.swipedelete.core.storage.raw

import com.swipedelete.core.model.BatchId
import com.swipedelete.core.model.ItemId
import com.swipedelete.core.model.MediaItem
import com.swipedelete.core.model.SourceRef
import com.swipedelete.core.storage.DeleteResult
import com.swipedelete.core.storage.Destination
import com.swipedelete.core.storage.MoveResult
import com.swipedelete.core.storage.QuarantineResult
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Host tests for [RawStorageGateway] over real temporary directories.
 *
 * These are the AC-10 enforcement tests (spec §4.2 condition 2, §4.6): the
 * Tier-2 raw gateway exists only for **non-media** management and must refuse
 * media at every entry point. They also pin the §9.4 honesty contract (caps set
 * an explicit `truncated` flag; unreadable directories are reported) that Task
 * 20's `DocumentTreeWalker` established for the SAF walker.
 *
 * The gateway is host-testable because both Android dependencies are injected:
 * the All Files Access flag is a constructor argument (and the environment probe
 * is a function parameter of [RawStorageGateway.fromEnvironment]), and
 * `capacityOf` / `listChildren` default to the real `StatFs` / `File.listFiles`
 * but can be replaced in a test. No test depends on the device's real
 * permission state or on an OS-level unreadable directory.
 */
class RawStorageGatewayTest {

    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun cleanUp() {
        tempDirs.forEach { runCatching { it.deleteRecursively() } }
        tempDirs.clear()
    }

    private fun newRoot(): File =
        Files.createTempDirectory("raw-gateway-test").toFile().also { tempDirs += it }

    private fun gateway(root: File): RawStorageGateway =
        RawStorageGateway(roots = listOf(root), hasAllFilesAccess = true)

    private fun dir(parent: File, name: String): File =
        File(parent, name).apply { mkdirs() }

    private fun writeFile(parent: File, name: String, content: ByteArray = ByteArray(4)): File =
        File(parent, name).apply {
            parentFile?.mkdirs()
            writeBytes(content)
        }

    private fun itemFor(file: File, source: SourceRef, mime: String): MediaItem {
        val relative = file.path
            .removePrefix(source.treeUri)
            .trimStart('\\', '/')
            .replace('\\', '/')
        return MediaItem(
            id = ItemId("${source.id.value}:$relative"),
            uri = file.path,
            path = file.path,
            relativePath = relative,
            mediaStoreId = null,
            mime = mime,
            sizeBytes = file.length(),
            width = null,
            height = null,
            durationMs = null,
            dateAdded = 0L,
            dateModified = file.lastModified(),
            bucket = null,
            sourceId = source.id,
            hash = null,
        )
    }

    /** `FF D8 FF` — the JPEG magic. */
    private val jpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10)

    /** A minimal `ftyp`-branded MP4/MOV header (`ftyp` at offset 4, brand `isom`). */
    private val mp4Bytes =
        byteArrayOf(0x00, 0x00, 0x00, 0x18) + "ftyp".toByteArray() + "isom".toByteArray() + ByteArray(4)

    /** `PK\x03\x04` — the ZIP/APK magic, which must not be a media false positive. */
    private val zipBytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(16)

    @Test
    fun `raw gateway is only constructed when All Files Access is held`() = runTest {
        val root = newRoot()

        val failure = assertFailsWith<IllegalStateException> {
            RawStorageGateway(roots = listOf(root), hasAllFilesAccess = false)
        }
        assertTrue(
            failure.message.orEmpty().contains("All Files Access"),
            "the refusal must name All Files Access, was: ${failure.message}",
        )

        // fromEnvironment must refuse to build the gateway without the capability.
        assertNull(RawStorageGateway.fromEnvironment(roots = listOf(root), hasAllFilesAccess = { false }))
        assertNotNull(RawStorageGateway.fromEnvironment(roots = listOf(root), hasAllFilesAccess = { true }))
    }

    @Test
    fun `raw gateway refuses to enumerate media mime types`() = runTest {
        val root = newRoot()
        val gateway = gateway(root)
        val source = gateway.sources().single()

        // AC-10: media is not this gateway's job, whatever the filename says.
        val media = listOf(
            "photo.jpg" to "image/jpeg",
            "clip.mp4" to "video/mp4",
            "song.mp3" to "audio/mpeg",
            "mislabelled.bin" to "image/jpeg",
        )
        for ((name, mime) in media) {
            val item = itemFor(writeFile(root, name), source, mime)
            assertFailsWith<MediaNotPermittedException>("metadata must refuse $mime") { gateway.metadata(item) }
            assertFailsWith<MediaNotPermittedException>("openStream must refuse $mime") { gateway.openStream(item) }
        }

        // A non-media item is permitted.
        val apk = itemFor(writeFile(root, "installer.apk"), source, "application/vnd.android.package-archive")
        assertEquals(apk.sizeBytes, gateway.metadata(apk).sizeBytes)
        gateway.openStream(apk).close()
    }

    @Test
    fun `mutating a media item through the raw gateway is refused`() = runTest {
        val root = newRoot()
        val gateway = gateway(root)
        val source = gateway.sources().single()

        val mediaFile = writeFile(root, "photo.jpg")
        val mediaItem = itemFor(mediaFile, source, "image/jpeg")
        val apkFile = writeFile(root, "installer.apk")
        val apkItem = itemFor(apkFile, source, "application/vnd.android.package-archive")

        val moved = gateway.move(mediaItem, Destination(source))
        assertTrue(moved is MoveResult.Failed, "move of media must fail, was $moved")
        assertEquals("media_not_permitted", moved.reason)

        val quarantined = gateway.quarantine(listOf(mediaItem, apkItem), BatchId("b1"))
        assertTrue(quarantined is QuarantineResult.Partial, "was $quarantined")
        val quarantineFailures = quarantined.failures
        assertEquals(
            "media_not_permitted",
            quarantineFailures.first { it.itemId == mediaItem.id }.reason,
            "the media item must carry an explicit refusal, not be silently dropped",
        )
        assertTrue(
            quarantineFailures.any { it.itemId == apkItem.id } &&
                quarantineFailures.none { it.itemId == apkItem.id && it.reason == "media_not_permitted" },
            "a non-media item must still be processed in the same call",
        )

        val deleted = gateway.deletePermanently(listOf(mediaItem, apkItem))
        assertTrue(deleted is DeleteResult.Partial, "was $deleted")
        assertEquals(
            "media_not_permitted",
            deleted.failures.first { it.itemId == mediaItem.id }.reason,
        )
        assertEquals(1, deleted.deleted, "the non-media item must still be deleted in the same call")
        assertTrue(mediaFile.exists(), "the media file must be untouched (AC-10)")
        assertFalse(apkFile.exists(), "the non-media item must have been deleted")
    }

    @Test
    fun `enumerate skips media and reports how many it skipped`() = runTest {
        val root = newRoot()
        writeFile(root, "one.jpg")
        writeFile(root, "two.png")
        writeFile(root, "installer.apk")
        writeFile(root, "download.part")
        val gateway = gateway(root)
        val source = gateway.sources().single()

        val report = gateway.scan(source)
        assertEquals(
            setOf("installer.apk", "download.part"),
            report.items.map { it.relativePath }.toSet(),
            "media must be skipped completely",
        )
        assertEquals(2, report.mediaSkipped, "the skipped media count must be reported")

        val enumerated = gateway.enumerate(source, null).toList()
            .flatMap { it.items }
            .map { it.relativePath }
            .toSet()
        assertEquals(setOf("installer.apk", "download.part"), enumerated)
    }

    @Test
    fun `empty folder detection ignores folders with a nomedia marker`() = runTest {
        val root = newRoot()
        val empty = dir(root, "truly-empty")
        val nomedia = dir(root, "hidden").also { writeFile(it, ".nomedia") }
        val parent = dir(root, "parent-only")
        val nestedEmpty = dir(parent, "nested-empty")
        val withFile = dir(root, "has-file").also { writeFile(it, "notes.txt") }

        val reported = gateway(root).emptyFolders().map { it.canonicalFile }.toSet()

        assertTrue(empty.canonicalFile in reported, "a truly empty directory is reported")
        assertFalse(nomedia.canonicalFile in reported, "a .nomedia marker excludes the directory")
        assertFalse(parent.canonicalFile in reported, "a directory with one entry is not empty")
        assertTrue(nestedEmpty.canonicalFile in reported, "the nested directory really is empty")
        assertFalse(withFile.canonicalFile in reported, "a directory containing a file is not empty")
        assertFalse(root.canonicalFile in reported, "a root is never reported as empty")
    }

    @Test
    fun `a root outside the given roots is never walked`() = runTest {
        val root = newRoot()
        val outside = newRoot()
        writeFile(root, "inside.apk")
        val outsideFile = writeFile(outside, "outside.apk")

        val gateway = gateway(root)
        val source = gateway.sources().single()

        assertEquals(
            listOf("inside.apk"),
            gateway.scan(source).items.map { it.relativePath },
            "nothing outside the declared roots may be enumerated",
        )

        // An item that names a path outside the roots is refused outright.
        val escaped = itemFor(outsideFile, source, "application/octet-stream")
        assertFailsWith<FileNotFoundException> { gateway.metadata(escaped) }
        assertFailsWith<FileNotFoundException> { gateway.openStream(escaped) }
        val escapeDelete = gateway.deletePermanently(listOf(escaped))
        assertTrue(escapeDelete is DeleteResult.Partial, "was $escapeDelete")
        assertEquals("outside_roots", escapeDelete.failures.single().reason)
        assertTrue(outsideFile.exists(), "a path outside the roots must never be deleted")

        // Invalid roots fail construction with a clear error.
        assertFailsWith<IllegalArgumentException> {
            RawStorageGateway(listOf(File(root, "does-not-exist")), hasAllFilesAccess = true)
        }
        assertFailsWith<IllegalArgumentException> {
            RawStorageGateway(listOf(writeFile(root, "a-file.apk")), hasAllFilesAccess = true)
        }
        assertFailsWith<IllegalArgumentException> {
            RawStorageGateway(listOf(File.listRoots().first()), hasAllFilesAccess = true)
        }
    }

    @Test
    fun `caps are enforced with an explicit truncated flag`() = runTest {
        val root = newRoot()
        repeat(10) { index -> writeFile(root, "file$index.apk") }
        val gateway = RawStorageGateway(roots = listOf(root), hasAllFilesAccess = true, maxEntries = 3)

        val report = gateway.scan(gateway.sources().single())

        assertTrue(report.truncated, "hitting the entry cap must set the explicit truncated flag")
        assertEquals(3, report.entriesScanned, "the cap must stop the scan")
        assertEquals(3, report.items.size)
        assertEquals(0, report.unreadableEntries)
    }

    @Test
    fun `unreadable directories are reported, not silently skipped`() = runTest {
        // A directory cannot portably be made unreadable on Windows, so this
        // uses the same seam pattern Task 20 used for the SAF walker: the raw
        // gateway takes its one filesystem primitive (`listChildren`) as an
        // injected function, and the test makes one directory fail to list.
        val root = newRoot()
        dir(root, "readable").let { writeFile(it, "keep.apk") }
        dir(root, "unreadable").let { writeFile(it, "unreachable.apk") }

        val gateway = RawStorageGateway(
            roots = listOf(root),
            hasAllFilesAccess = true,
            listChildren = { directory ->
                if (directory.name == "unreadable") null else directory.listFiles()?.toList()
            },
        )

        val report = gateway.scan(gateway.sources().single())

        assertEquals(1, report.unreadableEntries, "an unreadable directory must be counted")
        assertTrue(
            report.errors.any { it.path.replace('\\', '/').endsWith("unreadable") },
            "the unreadable directory must be named in the report, was ${report.errors}",
        )
        assertEquals(listOf("readable/keep.apk"), report.items.map { it.relativePath })
    }

    @Test
    fun `media is detected by content, not only by extension`() = runTest {
        // The AC-10 hole: a JPEG with no extension and an MP4 renamed `.tmp`
        // both have a non-media name, so extension-only classification would
        // enumerate and delete media through the raw gateway.
        val root = newRoot()
        writeFile(root, "photo", jpegBytes)
        writeFile(root, "clip.tmp", mp4Bytes)
        writeFile(root, "installer.apk", zipBytes)
        writeFile(root, "notes.txt", "plain text".toByteArray())

        val gateway = gateway(root)
        val source = gateway.sources().single()
        val report = gateway.scan(source)

        assertEquals(
            setOf("installer.apk", "notes.txt"),
            report.items.map { it.relativePath }.toSet(),
            "content-detected media must not be enumerated",
        )
        assertEquals(2, report.mediaSkippedByContent, "both sniffs must be counted separately")
        assertEquals(0, report.mediaSkippedByExtension)
        assertEquals(2, report.mediaSkipped)

        val photo = itemFor(File(root, "photo"), source, "application/octet-stream")
        val clip = itemFor(File(root, "clip.tmp"), source, "application/octet-stream")
        assertFailsWith<MediaNotPermittedException> { gateway.metadata(photo) }
        assertFailsWith<MediaNotPermittedException> { gateway.openStream(clip) }

        val deleted = gateway.deletePermanently(listOf(photo, clip))
        assertTrue(deleted is DeleteResult.Partial, "was $deleted")
        assertEquals(2, deleted.failures.count { it.reason == "media_not_permitted" })
        assertEquals(0, deleted.deleted)
        assertTrue(File(root, "photo").exists(), "content-detected media must never be deleted (AC-10)")
        assertTrue(File(root, "clip.tmp").exists())

        // The ZIP/APK signature is not a media false positive.
        val apk = itemFor(File(root, "installer.apk"), source, "application/vnd.android.package-archive")
        assertEquals(apk.sizeBytes, gateway.metadata(apk).sizeBytes)
        gateway.openStream(apk).close()
    }

    @Test
    fun `a file whose bytes cannot be read is treated as media`() = runTest {
        val root = newRoot()
        val opaque = writeFile(root, "mystery", byteArrayOf(0x00, 0x01, 0x02, 0x03))
        val gateway = RawStorageGateway(
            roots = listOf(root),
            hasAllFilesAccess = true,
            readBytes = { file ->
                if (file.name == "mystery") null else RawMediaPolicy.readLeadingBytes(file)
            },
        )
        val source = gateway.sources().single()

        val report = gateway.scan(source)
        assertTrue(report.items.isEmpty(), "an unreadable body is refused, not enumerated")
        assertEquals(1, report.unreadableMediaSkipped)

        val item = itemFor(opaque, source, "application/octet-stream")
        assertFailsWith<MediaNotPermittedException> { gateway.metadata(item) }
        val deleted = gateway.deletePermanently(listOf(item))
        assertTrue(deleted is DeleteResult.Partial, "was $deleted")
        assertEquals("media_not_permitted", deleted.failures.single().reason)
        assertTrue(opaque.exists(), "an unreadable file must never be deleted")
    }

    @Test
    fun `a directory child outside the roots is never traversed`() = runTest {
        // `File.isDirectory`/`listFiles` follow symlinks, so a link inside a
        // root can point elsewhere. The seam feeds that child deterministically;
        // the symlink integration case is covered separately below.
        val root = newRoot()
        val outside = newRoot()
        writeFile(outside, "outside.apk")
        val rootDir = root.canonicalFile
        val outsideDir = outside.canonicalFile

        val gateway = RawStorageGateway(
            roots = listOf(root),
            hasAllFilesAccess = true,
            listChildren = { directory ->
                if (directory == rootDir) {
                    listOf(outsideDir) + rootDir.listFiles()!!.toList()
                } else {
                    directory.listFiles()?.toList()
                }
            },
        )

        val report = gateway.scan(gateway.sources().single())

        assertTrue(report.items.none { it.path.orEmpty().contains("outside") }, "nothing outside a root may be walked")
        assertEquals(1, report.outOfRootEntries, "a skipped out-of-root entry must be counted")
        assertTrue(report.errors.any { it.reason == "outside_root" }, "and it must be named: ${report.errors}")
    }

    @Test
    fun `a symlink inside a root pointing outside is not traversed`() = runTest {
        val root = newRoot()
        val outside = newRoot()
        writeFile(root, "inside.apk")
        writeFile(outside, "outside.apk")
        val link = File(root, "link")

        val created = try {
            Files.createSymbolicLink(link.toPath(), outside.canonicalFile.toPath())
            true
        } catch (error: Throwable) {
            false
        }

        if (created) {
            val report = gateway(root).scan(gateway(root).sources().single())
            assertEquals(listOf("inside.apk"), report.items.map { it.relativePath })
            assertEquals(1, report.outOfRootEntries)
            assertTrue(report.errors.any { it.reason == "outside_root" })
        } else {
            // Windows without Developer Mode refuses symlink creation. The
            // traversal guard is covered deterministically by the seam test
            // above; here the containment helper is asserted directly. The real
            // filesystem symlink case is delegated to the Task 26 device pass.
            val rootDir = root.canonicalFile
            assertTrue(RawPaths.isWithin(File(root, "inside.apk").canonicalFile, rootDir))
            assertFalse(RawPaths.isWithin(File(outside, "outside.apk").canonicalFile, rootDir))
            assertFalse(RawPaths.isWithin(outside.canonicalFile, rootDir))
        }
    }

    @Test
    fun `a declared root can never be deleted`() = runTest {
        val root = newRoot()
        writeFile(root, "inside.apk")
        val gateway = gateway(root)
        val source = gateway.sources().single()
        val rootItem = itemFor(root, source, "application/octet-stream")

        val result = gateway.deletePermanently(listOf(rootItem))

        assertTrue(result is DeleteResult.Partial, "was $result")
        assertEquals("refusing_to_delete_a_root", result.failures.single().reason)
        assertTrue(root.exists(), "a declared root must survive (spec §6.3)")
    }

    @Test
    fun `move refuses an unlistable destination instead of overwriting`() = runTest {
        val root = newRoot()
        val source = writeFile(root, "src.apk")
        val destination = dir(root, "dest")
        val gateway = RawStorageGateway(
            roots = listOf(root),
            hasAllFilesAccess = true,
            listChildren = { directory ->
                if (directory.name == "dest") null else directory.listFiles()?.toList()
            },
        )
        val item = itemFor(source, gateway.sources().single(), "application/vnd.android.package-archive")

        val result = gateway.move(item, Destination(gateway.sources().single(), relativePath = "dest"))

        assertTrue(result is MoveResult.Failed, "was $result")
        assertEquals("destination_unreadable", result.reason)
        assertTrue(source.exists(), "the source must be untouched")
        assertTrue(destination.exists())
    }

    @Test
    fun `an unlistable directory inside a delete subtree is treated as media-bearing`() = runTest {
        // AC-10: if a directory cannot be listed, the gateway cannot prove it
        // holds no media, so a recursive delete must be refused. Treating an
        // unreadable directory as a dead end would `deleteRecursively()` a
        // subtree it could not read — the unsafe direction.
        val root = newRoot()
        val subtree = dir(root, "subtree")
        val nested = dir(subtree, "nested")
        writeFile(nested, "notes.txt", "plain text".toByteArray())
        val gateway = RawStorageGateway(
            roots = listOf(root),
            hasAllFilesAccess = true,
            listChildren = { directory ->
                if (directory.name == "nested") null else directory.listFiles()?.toList()
            },
        )
        val source = gateway.sources().single()
        val item = itemFor(subtree, source, "application/octet-stream")

        val result = gateway.deletePermanently(listOf(item))

        assertTrue(result is DeleteResult.Partial, "an unreadable subtree must be refused, was $result")
        assertEquals(RawStorageGateway.MEDIA_NOT_PERMITTED, result.failures.single().reason)
        assertTrue(subtree.exists(), "an unreadable subtree must not be deleted recursively")
        assertTrue(File(nested, "notes.txt").exists(), "bytes the gateway could not see must survive")
    }
}
