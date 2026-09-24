package com.swipedelete.core.storage.scan

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.swipedelete.core.model.SourceId
import com.swipedelete.core.model.SourceKind
import com.swipedelete.core.model.SourceRef
import com.swipedelete.core.storage.raw.RawStorageGateway
import com.swipedelete.core.storage.saf.DocumentTreeWalker
import com.swipedelete.core.storage.saf.FakeDocumentTree
import com.swipedelete.core.storage.saf.PersistedGrantStore
import com.swipedelete.core.storage.saf.SafStorageGateway
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The read-only `SourceScanPort` (D1). It exists because M0 left no way to
 * surface the SAF walker's truncation flag through the frozen `StorageGateway`:
 * the only injectable storage type is the decorated gateway, and
 * `enumerateDetailed`/`WalkPage` are unreachable through it
 * (`docs/M0-ACCEPTANCE.md`, Finding 1).
 *
 * Two properties are pinned here:
 * 1. **Honest.** A capped walk reports `truncated` on the last page instead of
 *    presenting a partial listing as complete (§9.4).
 * 2. **Read-only.** The port yields exactly what the frozen `enumerate` yields —
 *    it adds the progress the frozen `Page` cannot carry and changes nothing
 *    else. Both concrete walkers (SAF and raw) implement it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SourceScanPortTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val treeUri: String = "content://com.swipedelete.test/tree/root"

    private val source = SourceRef(
        id = SourceId("src-scan"),
        treeUri = treeUri,
        displayName = "Downloads",
        access = SourceRef.Access.RW,
        kind = SourceKind.SAF,
        itemCount = null,
        totalBytes = null,
        grantedAt = 0L,
    )

    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun cleanUp() {
        tempDirs.forEach { runCatching { it.deleteRecursively() } }
        tempDirs.clear()
    }

    private fun safTree(files: Int): FakeDocumentTree =
        FakeDocumentTree(treeUri = treeUri, rootDocumentId = "root").apply {
            repeat(files) { index -> addFile("root", "f$index", "f$index.apk") }
        }

    private fun safGateway(tree: FakeDocumentTree, maxEntries: Int): SafStorageGateway =
        SafStorageGateway(
            context = context,
            persisted = object : PersistedGrantStore {
                override suspend fun sources(): List<SourceRef> = listOf(source)
            },
            access = tree,
            walker = DocumentTreeWalker(tree, pageSize = 2, maxEntries = maxEntries),
        )

    @Test
    fun `scan reports truncation when the walker hits its entry cap`() = runTest {
        val gateway = safGateway(safTree(files = 6), maxEntries = 3)
        val pages = gateway.scan(source, null).toList()

        assertTrue(pages.size >= 2, "the capped walk must still be paged, was ${pages.size} page(s)")
        assertTrue(
            pages.last().truncated,
            "a capped walk must say so, not look complete",
        )
        assertEquals(3, pages.last().scanned, "scanned must be the cumulative count the walker reported")
    }

    @Test
    fun `scan yields the same items as the frozen enumerate`() = runTest {
        val gateway = safGateway(safTree(files = 5), maxEntries = 100_000)

        val enumerated = gateway.enumerate(source, null).toList().flatMap { it.items }
        val scanned = gateway.scan(source, null).toList().flatMap { it.items }

        assertEquals(
            enumerated.map { it.id },
            scanned.map { it.id },
            "the port must not change which items the frozen enumerate returns",
        )
        assertEquals(enumerated, scanned, "the read-only port must not alter the items themselves")
    }

    @Test
    fun `raw gateway implements the read-only port and surfaces its truncation`() = runTest {
        val root = Files.createTempDirectory("scan-port-raw-").toFile().also { tempDirs += it }
        repeat(6) { index -> File(root, "file$index.apk").writeBytes(ByteArray(4)) }

        val gateway = RawStorageGateway(roots = listOf(root), hasAllFilesAccess = true, maxEntries = 3)
        val port: SourceScanPort = gateway
        val rawSource = gateway.sources().single()

        val pages = port.scan(rawSource).toList()
        assertTrue(pages.last().truncated, "the raw entry cap must be surfaced, not hidden")
        assertEquals(3, pages.last().scanned)

        assertEquals(
            gateway.enumerate(rawSource, null).toList().flatMap { it.items }.map { it.id },
            port.scan(rawSource).toList().flatMap { it.items }.map { it.id },
            "the raw port must yield exactly the frozen enumerate's items",
        )
    }
}
