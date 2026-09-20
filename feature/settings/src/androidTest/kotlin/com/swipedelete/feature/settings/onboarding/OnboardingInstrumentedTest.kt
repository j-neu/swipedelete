package com.swipedelete.feature.settings.onboarding

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.swipedelete.core.common.Clock
import com.swipedelete.core.model.SourceId
import com.swipedelete.feature.settings.grants.ContentResolverGrantStore
import com.swipedelete.feature.settings.grants.GrantMutator
import com.swipedelete.feature.settings.grants.PersistResult
import com.swipedelete.feature.settings.grants.SourceGrantRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The device pass for M0-T16: the real `ACTION_OPEN_DOCUMENT_TREE` grant path
 * and the V2 cap check (SPEC 4.5 `[OPEN]` / `docs/ANDROID-CONSTRAINTS.md` V2).
 *
 * **This test is written but has NOT been run.** There is no booted device in
 * the M0-T16 session; the device pass is M0-T26 and runs:
 * `./gradlew :feature:settings:connectedDebugAndroidTest`.
 *
 * The two cap/revoke legs need real grants, which only the system picker can
 * create (no automation drives a user's folder choice), so they are gated with
 * [assumeTrue] and skipped unless the device has been prepared by granting
 * `SourceGrantRepository.MAX_GRANTS` trees by hand first. The V2 empirical
 * question — what the platform actually does at and beyond 20 grants on a real
 * device — is logged by `the 21st grant ...` for the report.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = ContentResolverGrantStore(context)
    private val repository = SourceGrantRepository(
        grants = store,
        mutator = store,
        clock = object : Clock {
            override fun nowMillis(): Long = System.currentTimeMillis()
        },
    )

    private fun heldTreeUris(): List<String> =
        context.contentResolver.persistedUriPermissions.map { it.uri.toString() }

    @Test
    fun `onboarding asks the system for a persisted tree grant with read and write`() {
        val intent = ActivityResultContracts.OpenDocumentTree().createIntent(context, null)

        assertEquals(
            "onboarding must use the system tree picker, not a permission dialog",
            Intent.ACTION_OPEN_DOCUMENT_TREE,
            intent.action,
        )
        assertEquals(
            "a destination folder must be granted read+write",
            GrantMutator.FLAG_READ or GrantMutator.FLAG_WRITE,
            GrantMutator.FLAG_READ_WRITE,
        )
    }

    @Test
    fun `the 21st grant is refused and the platform grant set is unchanged`() = runBlocking {
        val held = heldTreeUris()
        val osCap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) 512 else 128
        Log.i(
            TAG,
            "V2 device check: ${held.size} persisted grants held; OS cap $osCap on API ${Build.VERSION.SDK_INT}",
        )
        assumeTrue(
            "Manual setup: grant ${SourceGrantRepository.MAX_GRANTS} trees with the system picker first",
            held.size >= SourceGrantRepository.MAX_GRANTS,
        )

        val before = repository.grants().first().map { it.id }.toSet()
        val result = repository.persist(held.first(), GrantMutator.FLAG_READ)

        assertEquals(PersistResult.GrantCapReached, result)
        assertEquals(
            "a refused 21st grant must not evict any of the existing grants",
            before,
            repository.grants().first().map { it.id }.toSet(),
        )
        Log.i(TAG, "V2 device check: the 21st grant was refused and ${before.size} grants survived intact")
    }

    @Test
    fun `revoking releases the grant and is idempotent`() = runBlocking {
        val held = heldTreeUris()
        assumeTrue("Manual setup: grant at least one tree with the system picker first", held.isNotEmpty())
        val target = held.first()

        store.revoke(SourceId(target))

        assertFalse(
            "the revoked tree must no longer be a persisted grant",
            heldTreeUris().contains(target),
        )
        // A second revoke of the same id is a no-op, not an error.
        store.revoke(SourceId(target))
        assertFalse(heldTreeUris().contains(target))
    }

    private companion object {
        const val TAG: String = "OnboardingInstrumented"
    }
}
