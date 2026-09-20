package com.swipedelete.core.storage.saf

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.swipedelete.core.model.SourceId
import com.swipedelete.core.model.SourceKind
import com.swipedelete.core.model.SourceRef
import com.swipedelete.core.storage.ItemLocator
import com.swipedelete.core.storage.raw.RawStorageGateway
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Finding 4: reconciliation's existence probe must resolve a SAF item through
 * the gateway's own identity scheme, not through the shape of the string.
 *
 * A SAF item's `uri`/`path` is the synthetic display string
 * `"${treeUri}/${relativePath}"`, which is **not** a resolvable document URI;
 * the old `ContentResolver`-shape probe asked `openInputStream` to open it,
 * failed, and reported a present SAF file as absent. This test drives
 * [SafStorageGateway.exists] with the [FakeDocumentTree] seam (Task 20) so the
 * resolution is deterministic on the host.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafStorageGatewayLocatorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** `SafUri.treeDocumentId` must recover the same id the fake tree roots at. */
    private val treeUri: String = "content://com.swipedelete.test/tree/root"
    private val tree = FakeDocumentTree(treeUri = treeUri, rootDocumentId = "root")

    private val source = SourceRef(
        id = SourceId("src-saf"),
        treeUri = treeUri,
        displayName = "Downloads",
        access = SourceRef.Access.RW,
        kind = SourceKind.SAF,
        itemCount = null,
        totalBytes = null,
        grantedAt = 0L,
    )

    private fun gateway(): SafStorageGateway = SafStorageGateway(
        context = context,
        persisted = object : PersistedGrantStore {
            override suspend fun sources(): List<SourceRef> = listOf(source)
        },
        access = tree,
    )

    @Test
    fun `a present SAF synthetic path resolves true`() = runTest {
        tree.addFile("root", "root/a.gif", "a.gif")

        assertTrue(
            gateway().exists(SafUri.join(treeUri, "a.gif")),
            "a synthetic tree URI for a present document must resolve true",
        )
    }

    @Test
    fun `an absent SAF synthetic path resolves false`() = runTest {
        assertFalse(
            gateway().exists(SafUri.join(treeUri, "missing.gif")),
            "a synthetic tree URI for an absent document must resolve false",
        )
    }

    @Test
    fun `a synthetic path under a tree with no grant resolves false`() = runTest {
        assertFalse(
            gateway().exists("content://other/tree/revoked%3Adocs/secret.txt"),
            "a URI naming an ungranted tree must not resolve",
        )
    }

    @Test
    fun `both concrete gateways implement the locator capability`() {
        assertTrue(
            ItemLocator::class.java.isAssignableFrom(SafStorageGateway::class.java),
            "SafStorageGateway must implement ItemLocator",
        )
        assertTrue(
            ItemLocator::class.java.isAssignableFrom(RawStorageGateway::class.java),
            "RawStorageGateway must implement ItemLocator",
        )
    }
}
