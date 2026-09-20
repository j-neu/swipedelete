package com.swipedelete.core.storage.saf

import com.swipedelete.core.model.SourceId
import com.swipedelete.core.model.SourceKind
import com.swipedelete.core.model.SourceRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Host-JVM tests for [DocumentTreeWalker] against [FakeDocumentTree].
 *
 * These replace the brief's Robolectric `DocumentsContract` fixtures:
 * Robolectric cannot fake a real `DocumentsProvider`, so the walker is tested
 * through the `DocumentTreeAccess` seam instead. The four behaviours below are
 * the M0-T11 contract — §9.4 "a scan is never silently incomplete or dishonest".
 */
class SafDocumentTreeWalkerTest {

    private val treeUri = "tree://test"

    private fun source(): SourceRef = SourceRef(
        id = SourceId("src-saf"),
        treeUri = treeUri,
        displayName = "Tree",
        access = SourceRef.Access.RW,
        kind = SourceKind.SAF,
        itemCount = null,
        totalBytes = null,
        grantedAt = 0L,
    )

    /** `root/d0/d1/…/d(n-1)`, a file in each directory, and one file at the very bottom. */
    private fun deepChain(levels: Int): FakeDocumentTree {
        val tree = FakeDocumentTree(treeUri = treeUri)
        var parent = tree.rootDocumentId
        repeat(levels) { i ->
            tree.addDirectory(parent, "d$i", "d$i")
            tree.addFile("d$i", "f$i", "f$i.gif")
            parent = "d$i"
        }
        tree.addFile(parent, "deep", "deep.gif")
        return tree
    }

    private fun wideTree(files: Int): FakeDocumentTree {
        val tree = FakeDocumentTree(treeUri = treeUri)
        repeat(files) { i ->
            val name = "f" + i.toString().padStart(2, '0') + ".gif"
            tree.addFile(tree.rootDocumentId, "f$i", name)
        }
        return tree
    }

    @Test
    fun `walker pages a deep tree and honours the depth and entry caps`() = runTest {
        val deep = deepChain(40)
        val deepPages = DocumentTreeWalker(deep, pageSize = 1, maxDepth = 5, maxEntries = 100_000)
            .walk(source(), deep.rootDocumentId)
            .toList()

        assertTrue(deepPages.size >= 3, "expected at least three pages, got ${deepPages.size}")
        val deepPaths = deepPages.flatMap { it.items }.map { it.relativePath }
        val deepNames = deepPaths.map { it?.substringAfterLast('/') }
        assertEquals(deepPaths.distinct(), deepPaths, "pages must be contiguous: no repeated item")
        assertTrue("f0.gif" in deepNames)

        // d0..d3 are at depths 1..4 and are expanded, so f0..f3 (depths 2..5) are
        // emitted; d4 sits at the cap and is not expanded, so f4 and 'deep' are not.
        assertTrue("f3.gif" in deepNames)
        assertFalse("f4.gif" in deepNames, "a file below the depth cap must not be reported as scanned")
        assertFalse(deepNames.any { it == "deep.gif" })
        assertTrue(deepPages.last().progress.truncated, "the depth cap must set the explicit truncated flag")
        assertTrue(deepPages.last().progress.maxDepth <= 5)

        val wide = wideTree(50)
        val widePages = DocumentTreeWalker(wide, pageSize = 4, maxDepth = 32, maxEntries = 10)
            .walk(source(), wide.rootDocumentId)
            .toList()
        val wideItems = widePages.flatMap { it.items }

        assertEquals(10, wideItems.size, "the entry cap must stop the walk")
        assertEquals(10, widePages.last().progress.entriesScanned)
        assertTrue(widePages.last().progress.truncated, "the entry cap must set the explicit truncated flag")
        assertTrue(widePages.size >= 3, "the capped walk must still be paged")
        assertEquals(wideItems.map { it.id }.distinct(), wideItems.map { it.id }, "no gaps and no repeats")
    }

    @Test
    fun `walker is cancellable between pages and reports partial progress`() = runTest {
        val tree = wideTree(20)
        val walker = DocumentTreeWalker(tree, pageSize = 3, maxDepth = 32, maxEntries = 1_000)
        val full = walker.walk(source(), tree.rootDocumentId).toList()
        assertTrue(full.size >= 3)

        val seen = mutableListOf<WalkPage>()
        val job = launch {
            walker.walk(source(), tree.rootDocumentId).collect { page ->
                seen += page
                if (seen.size == 1) throw CancellationException("stop between pages")
            }
        }
        job.join()

        assertEquals(1, seen.size, "the collection must stop at the cancellation")
        assertEquals(
            full.first().items.map { it.id },
            seen.single().items.map { it.id },
            "the page already emitted must remain valid",
        )
        assertTrue(seen.single().progress.entriesScanned > 0, "partial progress must be reported, not lost")
    }

    @Test
    fun `deleted and unreadable children are reported, not silently skipped`() = runTest {
        val tree = FakeDocumentTree(treeUri = treeUri)
        tree.addFile(tree.rootDocumentId, "ok", "ok.gif")
        tree.addFile(tree.rootDocumentId, "gone", "gone.gif")
        tree.addDirectory(tree.rootDocumentId, "locked", "locked")
        tree.addFile("locked", "hidden", "hidden.gif")
        tree.failMetadata("gone", "FileNotFoundException: gone.gif")
        tree.failChildren("locked", "SecurityException: tree grant revoked")

        val pages = DocumentTreeWalker(tree, pageSize = 10, maxDepth = 32, maxEntries = 1_000)
            .walk(source(), tree.rootDocumentId)
            .toList()

        assertEquals(
            listOf("ok.gif"),
            pages.flatMap { it.items }.map { it.relativePath },
            "an unreadable child must not be emitted as a success",
        )
        val errors = pages.flatMap { it.progress.errors }
        assertEquals(2, errors.size, "every unreadable entry must be surfaced")
        assertTrue(errors.any { it.documentId == "gone" && it.reason.contains("FileNotFound") })
        assertTrue(errors.any { it.documentId == "locked" && it.reason.contains("Security") })
        assertEquals(2, pages.last().progress.unreadableEntries)
    }

    @Test
    fun `every page carries a resumable PageToken`() = runTest {
        val tree = wideTree(10)
        val walker = DocumentTreeWalker(tree, pageSize = 3, maxDepth = 32, maxEntries = 1_000)
        val all = walker.walk(source(), tree.rootDocumentId).toList()
        assertTrue(all.size >= 3)

        assertTrue(all.dropLast(1).all { it.next != null }, "every non-final page is resumable")
        assertNull(all.last().next, "the final page has no continuation")

        val fromFirst = walker.walk(source(), tree.rootDocumentId, all.first().next).toList()
        assertEquals(
            all.drop(1).flatMap { it.items }.map { it.id },
            fromFirst.flatMap { it.items }.map { it.id },
            "resuming from a token reproduces the remaining items in order",
        )

        val mid = 1
        val fromMid = walker.walk(source(), tree.rootDocumentId, all[mid].next).toList()
        assertEquals(
            all.drop(mid + 1).flatMap { it.items }.map { it.id },
            fromMid.flatMap { it.items }.map { it.id },
        )
        assertEquals(all.drop(1).map { it.next }, fromFirst.map { it.next }, "tokens must be deterministic")
    }
}
