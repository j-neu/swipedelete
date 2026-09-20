package com.swipedelete.feature.settings.grants

import com.swipedelete.core.model.SourceRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/**
 * The repository is the app's own grant policy: the cap (SPEC 4.5 [OPEN], V2),
 * the ordering the grants screen shows, and revoke. It is constructed without
 * Android — no `Context`, no `ContentResolver` — so all of that is exercised
 * here against fake ports. `Uri` never reaches this class: the mutator owns it.
 */
class SourceGrantRepositoryTest {

    private val clock = FakeClock()

    @Test
    fun `persisting a tree grants a source with its display name and access mode`() = runTest {
        val store = FakeGrantStore()
        val mutator = FakeGrantMutator(store, clock)
        val repository = SourceGrantRepository(grants = store, mutator = mutator, clock = clock)

        val result = repository.persist(TREE_DOWNLOAD, GrantMutator.FLAG_READ_WRITE)

        assertIs<PersistResult.Persisted>(result)
        assertEquals("Download", result.source.displayName, "the grant carries the folder's display name")
        assertEquals(SourceRef.Access.RW, result.source.access, "read+write was requested, so the grant is RW")
        assertEquals(TREE_DOWNLOAD, result.source.treeUri, "the persisted tree URI is the source identity")

        assertEquals(
            listOf(TREE_DOWNLOAD),
            repository.grants().first().map { it.treeUri },
            "the new grant appears in grants()",
        )
    }

    @Test
    fun `a read-only request is granted as read-only`() = runTest {
        val store = FakeGrantStore()
        val repository = SourceGrantRepository(store, FakeGrantMutator(store, clock), clock)

        val result = repository.persist(TREE_DOWNLOAD, GrantMutator.FLAG_READ)

        assertIs<PersistResult.Persisted>(result)
        assertEquals(SourceRef.Access.RO, result.source.access)
    }

    @Test
    fun `the 21st grant is refused with GrantCapReached and the existing grants are unchanged`() = runTest {
        val store = FakeGrantStore()
        val mutator = FakeGrantMutator(store, clock)
        val repository = SourceGrantRepository(grants = store, mutator = mutator, clock = clock)

        repeat(SourceGrantRepository.MAX_GRANTS) { index ->
            assertIs<PersistResult.Persisted>(
                repository.persist(treeUri(index), GrantMutator.FLAG_READ),
                "grant ${index + 1} of ${SourceGrantRepository.MAX_GRANTS} must be accepted",
            )
        }
        val before = repository.grants().first()
        assertEquals(SourceGrantRepository.MAX_GRANTS, before.size)
        val idsBefore = before.map { it.id }.toSet()

        val refused = repository.persist(treeUri(99), GrantMutator.FLAG_READ)

        assertEquals(PersistResult.GrantCapReached, refused)
        val after = repository.grants().first()
        assertEquals(SourceGrantRepository.MAX_GRANTS, after.size, "a refused grant must not be added")
        assertEquals(idsBefore, after.map { it.id }.toSet(), "the existing grants must be untouched, not merely equal in count")
        assertFalse(
            mutator.log.contains("persist:${treeUri(99)}"),
            "the cap must be enforced before the platform is asked, so the refusal cannot evict anything",
        )
    }

    @Test
    fun `revoking a grant removes it and never deletes any files`() = runTest {
        val store = FakeGrantStore()
        val mutator = FakeGrantMutator(store, clock)
        val repository = SourceGrantRepository(grants = store, mutator = mutator, clock = clock)
        assertIs<PersistResult.Persisted>(repository.persist(TREE_DOWNLOAD, GrantMutator.FLAG_READ_WRITE))
        val source = repository.grants().first().single()

        // A file inside the granted tree, so "nothing was deleted" is observable.
        val file = "$TREE_DOWNLOAD/photo.jpg"
        mutator.gateway.createFileAt(file, mime = "image/jpeg", sizeBytes = 1024L)
        val operationsBefore = mutator.gateway.faultInjector.operationCount()
        // Only the revoke's own effect is asserted below.
        mutator.log.clear()

        repository.revoke(source.id)

        assertTrue(
            repository.grants().first().isEmpty(),
            "the revoked grant must disappear from grants()",
        )
        assertEquals(
            listOf("release:${source.id.value}"),
            mutator.log,
            "revoke must only release the persistable permission",
        )
        assertTrue(
            mutator.gateway.fileExists(file),
            "revoking access must never delete or move a file inside the tree",
        )
        assertEquals(
            operationsBefore,
            mutator.gateway.faultInjector.operationCount(),
            "revoke must not reach any gateway operation",
        )
    }

    @Test
    fun `a revoked grant frees a slot under the cap`() = runTest {
        val store = FakeGrantStore()
        val repository = SourceGrantRepository(store, FakeGrantMutator(store, clock), clock)
        repeat(SourceGrantRepository.MAX_GRANTS) { index ->
            assertIs<PersistResult.Persisted>(repository.persist(treeUri(index), GrantMutator.FLAG_READ))
        }
        val revoked = repository.grants().first().first()
        assertEquals(PersistResult.GrantCapReached, repository.persist(treeUri(99), GrantMutator.FLAG_READ))

        repository.revoke(revoked.id)

        val result = repository.persist(treeUri(99), GrantMutator.FLAG_READ)
        assertIs<PersistResult.Persisted>(result)
        assertEquals(SourceGrantRepository.MAX_GRANTS, repository.grants().first().size)
    }

    @Test
    fun `a platform refusal is reported as GrantDenied and adds nothing`() = runTest {
        val store = FakeGrantStore()
        val repository = SourceGrantRepository(
            grants = store,
            mutator = FakeGrantMutator(store, clock, deny = true),
            clock = clock,
        )

        assertEquals(PersistResult.GrantDenied, repository.persist(TREE_DOWNLOAD, GrantMutator.FLAG_READ_WRITE))
        assertTrue(repository.grants().first().isEmpty())
    }
}
