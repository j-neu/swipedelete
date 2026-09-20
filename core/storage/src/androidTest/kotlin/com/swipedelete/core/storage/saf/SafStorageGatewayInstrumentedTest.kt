package com.swipedelete.core.storage.saf

import android.content.Context
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.swipedelete.core.common.Clock
import com.swipedelete.core.common.Sha256Hasher
import com.swipedelete.core.model.BatchId
import com.swipedelete.core.model.JournalEntry
import com.swipedelete.core.model.MediaItem
import com.swipedelete.core.model.SourceId
import com.swipedelete.core.model.SourceKind
import com.swipedelete.core.model.SourceRef
import com.swipedelete.core.storage.Destination
import com.swipedelete.core.storage.QuarantineResult
import com.swipedelete.core.storage.QuarantineStorageGateway
import com.swipedelete.core.storage.RestoreResult
import com.swipedelete.core.storage.StorageGateway
import com.swipedelete.core.storage.StorageGatewayContractTest
import com.swipedelete.core.storage.quarantine.QuarantineStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * The frozen [StorageGatewayContractTest] run against the **real**
 * `DocumentsContract` / `ContentResolver` path on a device or emulator.
 *
 * [TestDocumentsProvider] (declared in this source set's manifest) is a genuine
 * `DocumentsProvider`, asked for two roots. The contract fixture addresses items
 * as `"fake://primary/a.gif"`, so [RemappedAccess] translates that tree URI to
 * the provider's real tree URI and its empty root id to the provider root — the
 * test glue, not the gateway, owns that translation.
 *
 * **This test is written but has NOT been run**: there is no booted device in
 * the M0-T11 session. The device pass is M0-T26 and runs:
 * `./gradlew :core:storage:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class SafStorageGatewayInstrumentedTest : StorageGatewayContractTest() {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /**
     * Resolved at runtime from the installed provider, never rebuilt from a
     * package name, so it cannot disagree with the androidTest manifest's
     * `<applicationId>[AUTHORITY_SUFFIX]` declaration. The host-side
     * `SafAndroidTestWiringTest` checks that declaration.
     */
    private val authority: String = TestDocumentsProvider.resolveAuthority(context)
    private val primaryTree: String =
        DocumentsContract.buildTreeDocumentUri(authority, TestDocumentsProvider.PRIMARY).toString()
    private val exportTree: String =
        DocumentsContract.buildTreeDocumentUri(authority, TestDocumentsProvider.EXPORT).toString()

    private val exportSource = SourceRef(
        id = SourceId("src-export"),
        treeUri = exportTree,
        displayName = "Export",
        access = SourceRef.Access.RW,
        kind = SourceKind.SAF,
        itemCount = null,
        totalBytes = null,
        grantedAt = 0L,
    )

    private val grants = object : PersistedGrantStore {
        override suspend fun sources(): List<SourceRef> = listOf(PRIMARY_SOURCE)
    }

    private var remapped: RemappedAccess? = null
    private var gatewaySerial = 0

    override fun newGateway(items: List<MediaItem>): StorageGateway {
        reset(TestDocumentsProvider.PRIMARY)
        reset(TestDocumentsProvider.EXPORT)
        items.forEach { item ->
            val name = item.relativePath ?: item.uri.substringAfterLast('/')
            write(File(primaryDir(), name), name.toByteArray())
        }
        val access = RemappedAccess(
            delegate = SafDocumentsContractAccess(context),
            fromTree = PRIMARY_SOURCE.treeUri,
            toTree = primaryTree,
            toRootId = TestDocumentsProvider.PRIMARY,
        )
        remapped = access

        // The concrete gateway does not own a quarantine root (Task 20 fix):
        // the store is supplied, exactly as the production
        // `ProtectedRoots → Journaling → Quarantine → Concrete` stack does.
        val concrete = SafStorageGateway(
            context = context,
            persisted = grants,
            access = access,
        )
        val store = QuarantineStore(
            root = File(context.cacheDir, "${SafStorageGateway.QUARANTINE_DIR}/${gatewaySerial++}"),
            gateway = concrete,
            hasher = Sha256Hasher(),
            clock = object : Clock {
                override fun nowMillis(): Long = System.currentTimeMillis()
            },
        )
        return QuarantineStorageGateway(concrete, store)
    }

    override fun destinationWith(existingName: String): Destination {
        reset(TestDocumentsProvider.EXPORT)
        write(File(exportDir(), existingName), existingName.toByteArray())
        return Destination(exportSource)
    }

    override fun newFileAt(path: String) {
        write(fileForFakePath(path), path.toByteArray())
    }

    override fun fileExists(path: String): Boolean =
        if (path.startsWith("fake://")) fileForFakePath(path).exists() else File(path).exists()

    override fun quarantinePathFor(entry: JournalEntry): String =
        entry.toUri ?: error("quarantine entry has no path")

    /**
     * Arms the next stream open to fail with a [CancellationException], which is
     * exactly how a cancelled scope surfaces at the copy boundary in
     * [SafStorageGateway]: the store rethrows it untouched, so the original is
     * never deleted.
     */
    override suspend fun armCancellation(gateway: StorageGateway) {
        remapped?.cancelNextOpenStream()
    }

    /**
     * SAF AC-17 occupancy leg (finding 7). The inherited
     * `restoreRefusesToOverwriteAndReportsConflict` case pins the fixture's
     * synthetic `"fake://primary/a.gif"` original path, which is not absolute, so
     * it exercises the store's documented **pathless** refusal
     * (`no_raw_original_path`) — a genuine refusal, but not the occupancy branch
     * the test name suggests. This case gives the item a **real absolute**
     * original path (the bytes are still quarantined through SAF because
     * `relativePath` addresses the provider tree), so restore actually reaches the
     * occupancy check and refuses without overwriting.
     */
    @Test
    fun restoreRefusesAnOccupiedAbsoluteOriginalPath() = runTest {
        val target = File(context.cacheDir, "ac17-${System.nanoTime()}/a.gif")
        val item = item(name = "a.gif").copy(path = target.absolutePath, uri = target.absolutePath)
        val gateway = newGateway(listOf(item))

        val quarantined = gateway.quarantine(listOf(item), BatchId("ac17")) as QuarantineResult.Quarantined
        val entry = quarantined.entries.single()
        assertTrue(!target.exists(), "the quarantined original must have been removed")

        target.parentFile?.mkdirs()
        target.writeBytes("occupant".toByteArray())

        val restore = gateway.restore(entry)

        val conflict = restore as? RestoreResult.Conflict
            ?: fail("expected the occupied absolute target to be refused, was $restore")
        assertEquals(target.absolutePath, conflict.originalPath)
        assertEquals(target.absolutePath, conflict.occupiedBy, "the refusal must name the occupying file")
        assertNotEquals(
            QuarantineStore.NO_ORIGINAL_PATH,
            conflict.occupiedBy,
            "an absolute original path must reach the occupancy branch, not the pathless refusal",
        )
        assertContentEquals("occupant".toByteArray(), target.readBytes(), "the occupant must be untouched")
        assertTrue(fileExists(quarantinePathFor(entry)), "the quarantined copy must survive the conflict")

        target.parentFile?.deleteRecursively()
    }

    // --- Fixture plumbing ---

    private fun primaryDir(): File = TestDocumentsProvider.rootDir(TestDocumentsProvider.PRIMARY)

    private fun exportDir(): File = TestDocumentsProvider.rootDir(TestDocumentsProvider.EXPORT)

    private fun reset(root: String) {
        val dir = TestDocumentsProvider.rootDir(root)
        dir.deleteRecursively()
        dir.mkdirs()
    }

    private fun write(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }

    private fun fileForFakePath(path: String): File {
        val primaryPrefix = "${PRIMARY_SOURCE.treeUri}/"
        val exportPrefix = "${DESTINATION_SOURCE.treeUri}/"
        return when {
            path.startsWith(primaryPrefix) -> File(primaryDir(), path.removePrefix(primaryPrefix))
            path.startsWith(exportPrefix) -> File(exportDir(), path.removePrefix(exportPrefix))
            else -> File(path)
        }
    }

    /** Translates the contract's synthetic `fake://` tree URIs onto the real provider. */
    private class RemappedAccess(
        private val delegate: DocumentTreeAccess,
        private val fromTree: String,
        private val toTree: String,
        private val toRootId: String,
    ) : DocumentTreeAccess {

        @Volatile
        private var cancelNextOpenStream: Boolean = false

        fun cancelNextOpenStream() {
            cancelNextOpenStream = true
        }

        private fun tree(treeUri: String): String = if (treeUri == fromTree) toTree else treeUri

        private fun parent(treeUri: String, documentId: String): String =
            if (treeUri == fromTree && documentId.isEmpty()) toRootId else documentId

        private fun node(node: DocumentNode): DocumentNode = node.copy(treeUri = tree(node.treeUri))

        override suspend fun children(treeUri: String, parentDocumentId: String): List<DocumentNode> =
            delegate.children(tree(treeUri), parent(treeUri, parentDocumentId))

        override suspend fun metadata(node: DocumentNode): DocumentMetadata = delegate.metadata(node(node))

        override fun openStream(node: DocumentNode): InputStream {
            if (cancelNextOpenStream) throw CancellationException("armed cancellation")
            return delegate.openStream(node(node))
        }

        override fun openOutputStream(node: DocumentNode): OutputStream =
            delegate.openOutputStream(node(node))

        override suspend fun delete(node: DocumentNode): Boolean = delegate.delete(node(node))

        override suspend fun createDocument(
            treeUri: String,
            parentDocumentId: String,
            displayName: String,
            mime: String,
        ): DocumentNode = delegate
            .createDocument(tree(treeUri), parent(treeUri, parentDocumentId), displayName, mime)
            .copy(treeUri = treeUri)
    }
}
