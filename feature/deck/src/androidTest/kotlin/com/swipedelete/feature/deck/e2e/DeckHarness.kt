package com.swipedelete.feature.deck.e2e

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.provider.DocumentsContract
import androidx.room.Room
import com.swipedelete.core.common.Clock
import com.swipedelete.core.common.Sha256Hasher
import com.swipedelete.core.database.SwipeDeleteDatabase
import com.swipedelete.core.designsystem.SwipeDeleteTheme
import com.swipedelete.core.model.SourceId
import com.swipedelete.core.model.SourceKind
import com.swipedelete.core.model.SourceRef
import com.swipedelete.core.storage.JournalingStorageGateway
import com.swipedelete.core.storage.ProtectedRootsStorageGateway
import com.swipedelete.core.storage.QuarantineStorageGateway
import com.swipedelete.core.storage.StorageGateway
import com.swipedelete.core.storage.quarantine.QuarantineStore
import com.swipedelete.core.storage.saf.PersistedGrantStore
import com.swipedelete.core.storage.saf.SafStorageGateway
import com.swipedelete.core.storage.saf.TestDocumentsProvider
import com.swipedelete.feature.deck.domain.DeckBuilder
import com.swipedelete.feature.deck.domain.SlotTagSink
import com.swipedelete.feature.deck.session.GatewaySessionMutations
import com.swipedelete.feature.deck.session.MutationGate
import com.swipedelete.feature.deck.ui.DeckRoute
import com.swipedelete.feature.deck.ui.DeckViewModel
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The deck, assembled for a device the way the app assembles it, over files
 * that live only in this test app's private cache directory.
 *
 * - The source is a real SAF tree served by [TestDocumentsProvider], so
 *   enumeration, thumbnails, quarantine and delete all run the genuine
 *   `DocumentsContract` path (Tier 0, the app's default).
 * - The storage stack is the frozen production order,
 *   `ProtectedRoots → Journaling → Quarantine → Saf`, with a real on-disk Room
 *   journal and a real quarantine directory.
 * - The deck is the real [DeckViewModel] rendered by the real [DeckRoute].
 *
 * What it is **not**: the Hilt graph, `AppNavHost`, or `RecoveryCoordinator`.
 * Those live in `:app`, which a library's tests cannot depend on. The decorator
 * order is pinned separately by the `StorageGraphOrderTest` in `:app`.
 */
class DeckHarness(private val context: Context, itemCount: Int, imageSizePx: Int = 720) {

    private val runId = System.nanoTime()
    private val authority = TestDocumentsProvider.resolveAuthority(context)
    private val primaryDir = TestDocumentsProvider.rootDir(TestDocumentsProvider.PRIMARY)

    val quarantineRoot = File(context.cacheDir, "deck-e2e-quarantine/$runId")
    private val dbName = "deck-e2e-$runId.db"
    val db: SwipeDeleteDatabase =
        Room.databaseBuilder(context, SwipeDeleteDatabase::class.java, dbName).build()

    /** The source files, oldest first — the order a folder deck presents them in. */
    val files: List<File>

    val gateway: StorageGateway
    val viewModel: DeckViewModel

    init {
        primaryDir.deleteRecursively()
        primaryDir.mkdirs()
        val base = System.currentTimeMillis() - itemCount * 1000L
        files = (0 until itemCount).map { index ->
            File(primaryDir, "img-%04d.jpg".format(index)).also { file ->
                file.writeBytes(jpeg(index, imageSizePx))
                file.setLastModified(base + index * 1000L)
            }
        }

        val clock = object : Clock {
            override fun nowMillis(): Long = System.currentTimeMillis()
        }
        val source = SourceRef(
            id = SourceId("src-e2e"),
            treeUri = DocumentsContract.buildTreeDocumentUri(authority, TestDocumentsProvider.PRIMARY).toString(),
            displayName = "E2E",
            access = SourceRef.Access.RW,
            kind = SourceKind.SAF,
            itemCount = null,
            totalBytes = null,
            grantedAt = 0L,
        )
        val saf = SafStorageGateway(
            context = context,
            persisted = object : PersistedGrantStore {
                override suspend fun sources(): List<SourceRef> = listOf(source)
            },
        )
        val store = QuarantineStore(root = quarantineRoot, gateway = saf, hasher = Sha256Hasher(), clock = clock)
        gateway = ProtectedRootsStorageGateway(
            delegate = JournalingStorageGateway(
                delegate = QuarantineStorageGateway(saf, store),
                journal = db.journalDao(),
                clock = clock,
            ),
            unlocked = { emptySet() },
            quarantineRoot = quarantineRoot.absolutePath,
        )
        viewModel = DeckViewModel(
            builder = DeckBuilder(scanner = { saf }, gateway = gateway, clock = clock),
            gateway = gateway,
            sessions = db.deckSessionDao(),
            slots = db.slotDao(),
            gate = MutationGate { },
            mutations = GatewaySessionMutations(gateway),
            tags = SlotTagSink.NoOp,
            clock = clock,
        )
        DeckHostActivity.content = {
            SwipeDeleteTheme { DeckRoute(onOpenItem = {}, viewModel = viewModel) }
        }
    }

    /** Removes everything this run created. */
    fun close() {
        DeckHostActivity.content = {}
        db.close()
        context.deleteDatabase(dbName)
        quarantineRoot.deleteRecursively()
        primaryDir.deleteRecursively()
        primaryDir.mkdirs()
    }

    /** A distinct, real JPEG per index, so every card decodes a real image. */
    private fun jpeg(index: Int, sizePx: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.HSVToColor(floatArrayOf((index * 37 % 360).toFloat(), 0.6f, 0.9f)))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = sizePx / 4f
        }
        canvas.drawText(index.toString(), sizePx / 8f, sizePx / 2f, paint)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bitmap.recycle()
        return out.toByteArray()
    }
}
