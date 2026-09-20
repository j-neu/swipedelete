package com.swipedelete.app.di

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.swipedelete.core.common.Clock
import com.swipedelete.core.common.Sha256Hasher
import com.swipedelete.core.database.SwipeDeleteDatabase
import com.swipedelete.core.database.dao.JournalDao
import com.swipedelete.core.database.entity.JournalEntryEntity
import com.swipedelete.core.model.BatchId
import com.swipedelete.core.model.ItemId
import com.swipedelete.core.model.JournalState
import com.swipedelete.core.model.MediaItem
import com.swipedelete.core.model.OpKind
import com.swipedelete.core.model.SourceId
import com.swipedelete.core.storage.JournalingStorageGateway
import com.swipedelete.core.storage.ProtectedRootsStorageGateway
import com.swipedelete.core.storage.QuarantineResult
import com.swipedelete.core.storage.QuarantineStorageGateway
import com.swipedelete.core.storage.Reconciler
import com.swipedelete.core.storage.StorageGateway
import com.swipedelete.core.storage.fake.FakeStorageGateway
import com.swipedelete.core.storage.quarantine.QuarantineStore
import com.swipedelete.core.storage.saf.SafStorageGateway
import dagger.Binds
import dagger.Provides
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The integration spine of M0-T15: the frozen decorator order
 * `ProtectedRoots → Journaling → Quarantine → Concrete`, assembled through the
 * one construction function the Hilt module also calls
 * ([assembleStorageGraph]) so these tests cannot drift from production wiring.
 *
 * The property behind the order is AC-7-before-AC-1: a protected item is refused
 * by the outermost decorator **before any journal row is written**, so it can
 * never produce a journal row at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StorageGraphOrderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: SwipeDeleteDatabase
    private lateinit var quarantineRoot: File

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, SwipeDeleteDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        quarantineRoot = Files.createTempDirectory("sd-graph-quarantine-").toFile()
    }

    @After
    fun tearDown() {
        db.close()
        quarantineRoot.deleteRecursively()
    }

    /** 1. AC-7 is evaluated before AC-1, so a protected item writes no journal row at all. */
    @Test
    fun `a protected item produces no journal row`() = runTest {
        val protected = item(id = "camera-1", relativePath = "DCIM/Camera/IMG_0001.jpg")
        val fake = FakeStorageGateway(listOf(protected)).apply { register(protected, CONTENT) }
        val journal = db.journalDao()
        val graph = graph(fake, journal, exists = { fake.fileExists(it) })

        val result = graph.gateway.quarantine(listOf(protected), BatchId("b-protected"))

        val refused = result as? QuarantineResult.Partial
        assertNotNull(refused, "a protected item must be refused explicitly, was $result")
        assertEquals(0, refused.entries.size, "no entry may be reported for a refused item")
        assertEquals("protected_root", refused.failures.single().reason)
        assertEquals("DCIM/", refused.failures.single().cause)

        // The property: the journal was never touched, not merely compensated.
        assertTrue(journal.pending().isEmpty(), "a protected item must leave no PENDING row")
        assertTrue(journal.page(limit = 100, offset = 0).isEmpty(), "the journal must be completely empty")
        assertTrue(fake.fileExists(protected.path!!), "a refused item must not be moved")
    }

    /** 2. The write-ahead sequence for an allowed item reads PENDING, OP, DONE. */
    @Test
    fun `an allowed item is journaled PENDING before the operation and DONE after`() = runTest {
        val allowed = item(id = "allowed-1", relativePath = "Memes/allowed-1.gif")
        val delegate = FakeStorageGateway(listOf(allowed)).apply { register(allowed, CONTENT) }
        val trace = mutableListOf<String>()
        val journal = RecordingJournal(trace)
        val graph = graph(delegate, journal, exists = { delegate.fileExists(it) }, decorate = { TracingGateway(it, trace) })

        val result = graph.gateway.quarantine(listOf(allowed), BatchId("b-allowed"))

        assertTrue(result is QuarantineResult.Quarantined, "an allowed item must be quarantined, was $result")
        assertEquals(
            listOf("PENDING:allowed-1", "OP:allowed-1", "DONE:allowed-1"),
            trace,
            "the journal write must be strictly write-ahead of the file operation",
        )
        assertEquals(JournalState.DONE, journal.rows.single().state)
    }

    /** 3. One quarantine store, owned by the Quarantine layer; the concrete gateway holds none. */
    @Test
    fun `the graph contains exactly one quarantine store and the concrete gateways hold none`() {
        // The production provider itself, so this cannot drift from shipped
        // wiring: the concrete gateway is constructed *inside* it and never bound
        // (finding 1). The provider only hands back the decorated gateway and the
        // reconciler, so the concrete is reached by walking the decorator stack.
        val handle = StorageModule.provideStorageGraphHandle(
            context = context,
            persisted = StorageModule.provideGrantStore(context),
            journal = RecordingJournal(mutableListOf()),
            hasher = Sha256Hasher(),
            clock = FixedClock(NOW),
            quarantineRoot = quarantineRoot,
        )

        // The decorator stack is exactly ProtectedRoots -> Journaling -> Quarantine -> concrete.
        val protectedRoots = handle.gateway as ProtectedRootsStorageGateway
        val journaling = field(protectedRoots, "delegate") as JournalingStorageGateway
        val quarantineLayer = field(journaling, "delegate") as QuarantineStorageGateway
        val concrete = field(quarantineLayer, "delegate") as SafStorageGateway

        // The concrete gateway must not own a second quarantine root: its store is
        // null, which is also observable as an explicit not-configured refusal.
        assertNull(
            field(concrete, "quarantineStore"),
            "SafStorageGateway must be constructed with quarantineStore = null; the Quarantine layer owns the store",
        )

        // The decorator and the reconciler share the one store instance and its root.
        val decoratorStore = field(quarantineLayer, "store") as QuarantineStore
        val reconcilerStore = field(handle.reconciler, "quarantine") as QuarantineStore
        assertSame(decoratorStore, reconcilerStore, "the Reconciler must use the graph's store")
        assertEquals(quarantineRoot.absolutePath, decoratorStore.rootPath().absolutePath)

        // The production root is the app's external quarantine directory, so
        // ProtectedRootKind.INTERNAL is active for the directory the app writes into.
        val productionRoot = StorageModule.provideQuarantineRoot(context)
        assertEquals("quarantine", productionRoot.name)
        assertEquals(context.getExternalFilesDir(null) ?: context.filesDir, productionRoot.parentFile)
    }

    /** 3b. The store, the graph and every concrete gateway must not be reachable through Hilt. */
    @Test
    fun `the quarantine store, the storage graph and concrete gateways are not injectable`() {
        val modules = listOf(DatabaseModule, DispatchersModule, CapabilityModule, StorageModule)
        val bindingMethods = modules.flatMap { module ->
            module::class.java.declaredMethods.toList()
                .filter { method ->
                    method.isAnnotationPresent(Provides::class.java) || method.isAnnotationPresent(Binds::class.java)
                }
        }
        val bindings = bindingMethods.map { method -> method.returnType }

        // Guard against a vacuous pass: the scan must really be reading the modules.
        assertTrue(
            StorageGateway::class.java in bindings && Reconciler::class.java in bindings,
            "the module scan must find the real storage bindings, found=$bindings",
        )

        assertFalse(
            QuarantineStore::class.java in bindings,
            "QuarantineStore must not be a Hilt binding: an injectable store lets a consumer call " +
                "store()/restore()/scan() directly and bypass the write-ahead journal (AC-1)",
        )
        assertFalse(
            StorageGraph::class.java in bindings,
            "StorageGraph must not be a Hilt binding: it exposes the quarantine store",
        )

        // Finding 1, generalised to @Binds: no concrete StorageGateway
        // implementation may be a Dagger binding *or* a module-method dependency.
        // A @Binds method's return type is usually the interface, but its
        // parameter type can be the concrete implementation, so both are scanned.
        val concreteStorageTypes = bindingMethods
            .flatMap { method -> listOf(method.returnType) + method.parameterTypes.toList() }
            .filter { type ->
                StorageGateway::class.java.isAssignableFrom(type) &&
                    !type.isInterface &&
                    !java.lang.reflect.Modifier.isAbstract(type.modifiers)
            }
            .toSet()
        assertTrue(
            concreteStorageTypes.isEmpty(),
            "no concrete StorageGateway implementation may be a Hilt binding or dependency, " +
                "found=$concreteStorageTypes — binding one reopens the AC-1/AC-7 bypass",
        )

        // The only thing shared between the gateway and reconciler providers is the
        // provider-local handle, and its surface is exactly the two sanctioned
        // entry points — never the store, never the graph, never a concrete gateway.
        val exposed = StorageGraphHandle::class.java.declaredMethods
            .filter { method -> method.parameterCount == 0 && method.name.startsWith("get") }
            .map { method -> method.returnType }
            .toSet()
        assertEquals(
            setOf(StorageGateway::class.java, Reconciler::class.java),
            exposed,
            "the provider-local handle must expose only the gateway and the reconciler",
        )
    }

    /** 4. Interrupted quarantine + startup reconciliation, end to end. */
    @Test
    fun `reconciling an interrupted quarantine resolves the row`() = runTest {
        val interrupted = item(id = "interrupted-1", relativePath = "Memes/interrupted-1.gif")
        val fake = FakeStorageGateway(listOf(interrupted)).apply { register(interrupted, CONTENT) }
        val journal = db.journalDao()
        val graph = graph(fake, journal, exists = { fake.fileExists(it) })

        // Death between the verified copy and the original delete: the copy and its
        // sidecar survive, the original does not get removed, and the row is FAILED.
        fake.faultInjector.failOnOperation(OpKind.DELETE_PERMANENT, 1)
        val result = graph.gateway.quarantine(listOf(interrupted), BatchId("b-interrupted"))
        val partial = result as? QuarantineResult.Partial
        assertNotNull(partial, "the interrupted quarantine must report a per-item failure, was $result")
        assertEquals(1, journal.unresolved().size, "the interrupted call must leave one unresolved row")
        assertEquals(1, graph.quarantineStore.scan().size, "the verified copy must survive the interruption")

        val report = graph.reconciler.reconcile()

        assertTrue(report.unknown.isEmpty(), "an interrupted quarantine must reconcile, unknown=${report.unknown}")
        assertEquals(1, report.resolved.size)
        assertTrue(journal.unresolved().isEmpty(), "no row may be left unreported")
        assertTrue(journal.pending().isEmpty())
        assertEquals(JournalState.DONE, journal.page(limit = 10, offset = 0).single().state)

        // No loss: the rescued bytes are discoverable in the quarantine store.
        val quarantined = graph.quarantineStore.scan().single()
        assertEquals(interrupted.id.value, quarantined.sidecar.itemId)
        assertTrue(File(quarantined.path).isFile, "the rescued bytes must still be on disk")
    }

    // --- Harness ---

    /**
     * Builds the graph exactly as the Hilt module does, with the test's in-memory
     * journal and temp-dir quarantine root.
     */
    private fun graph(
        concrete: StorageGateway,
        journal: JournalDao,
        exists: suspend (String) -> Boolean = { File(it).isFile },
        decorate: (StorageGateway) -> StorageGateway = { it },
    ): StorageGraph = assembleStorageGraph(
        concrete = decorate(concrete),
        journal = journal,
        quarantineRoot = quarantineRoot,
        hasher = Sha256Hasher(),
        clock = FixedClock(NOW),
        exists = exists,
    )
}

/** A journal that records every commit into a shared trace, for ordering assertions. */
private class RecordingJournal(private val trace: MutableList<String>) : JournalDao {
    val rows = mutableListOf<JournalEntryEntity>()
    private val itemByEntry = mutableMapOf<String, String>()

    override suspend fun insertPending(entry: JournalEntryEntity): Long {
        trace += "PENDING:${entry.itemId}"
        itemByEntry[entry.id] = entry.itemId
        rows += entry
        return rows.size.toLong()
    }

    override suspend fun markOutcome(id: String, state: JournalState, error: String?): Int {
        trace += "${state.name}:${itemByEntry[id]}"
        rows.replaceAll { if (it.id == id) it.copy(state = state, error = error) else it }
        return 1
    }

    override suspend fun pending(): List<JournalEntryEntity> = rows.filter { it.state == JournalState.PENDING }

    override suspend fun unresolved(): List<JournalEntryEntity> =
        rows.filter { it.state == JournalState.PENDING || it.state == JournalState.FAILED }

    override suspend fun page(limit: Int, offset: Int): List<JournalEntryEntity> = rows.drop(offset).take(limit)

    override suspend fun byBatch(batchId: String): List<JournalEntryEntity> = rows.filter { it.batchId == batchId }
}

/** A concrete gateway that records the moment the quarantine copy reads its source. */
private class TracingGateway(
    private val delegate: StorageGateway,
    private val trace: MutableList<String>,
) : StorageGateway by delegate {
    override fun openStream(item: MediaItem): InputStream {
        trace += "OP:${item.id.value}"
        return delegate.openStream(item)
    }
}

private class FixedClock(private val now: Long) : Clock {
    override fun nowMillis(): Long = now
}

/**
 * Reads a Kotlin `private val` constructor property by name. The graph's shape is
 * the thing under test and the core types expose no accessors for their
 * collaborators, so the test reaches for the field directly rather than growing
 * production APIs only for assertions.
 */
private fun field(target: Any, name: String): Any? {
    val declared = generateSequence(target.javaClass as Class<*>) { it.superclass }
        .mapNotNull { type -> runCatching { type.getDeclaredField(name) }.getOrNull() }
        .firstOrNull()
    assertNotNull(declared, "no field '$name' on ${target.javaClass.name}")
    declared.isAccessible = true
    return declared.get(target)
}

/** `QuarantineStore.root` is a `private val`; mirror it for the root assertion. */
private fun QuarantineStore.rootPath(): File = field(this, "root") as File

private fun item(id: String, relativePath: String): MediaItem = MediaItem(
    id = ItemId(id),
    uri = "/storage/emulated/0/$relativePath",
    path = "/storage/emulated/0/$relativePath",
    relativePath = relativePath,
    mediaStoreId = null,
    mime = FakeStorageGateway.mimeFor(relativePath),
    sizeBytes = CONTENT.size.toLong(),
    width = null,
    height = null,
    durationMs = null,
    dateAdded = 0L,
    dateModified = NOW,
    bucket = null,
    sourceId = SourceId("src-graph"),
    hash = null,
)

private const val NOW = 1_700_000_000_000L
private val CONTENT = "swipe-delete-content".toByteArray()
