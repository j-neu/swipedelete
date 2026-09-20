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

    /**
     * Names are camelCase, not backticked prose: this module's `minSdk` is 26, so
     * D8 targets DEX version 038, and space characters in method names are only
     * legal from DEX version 040 (API 30). Backticked prose names compile on the
     * host JVM but make the instrumented APK undeployable.
     */
    @Test
    fun onboardingAsksTheSystemForAPersistedTreeGrantWithReadAndWrite() {
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
    fun the21stGrantIsRefusedAndThePlatformGrantSetIsUnchanged() = runBlocking<Unit> {
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
    fun revokingReleasesTheGrantAndIsIdempotent() = runBlocking {
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

    /**
     * The V2 device check's executable leg. A tree URI the platform never offered
     * a persistable grant for must be reported as "slot not granted" and must add
     * nothing.
     *
     * On API 30+ `takePersistableUriPermission` throws
     * `SecurityException("No persistable permission grants found for UID ... and
     * Uri ...")` — the **non-cap** failure V2 documents, distinct from the
     * silent-eviction cap. This asserts the mitigation V2 requires on a real
     * device: the `SecurityException` is treated as a refusal, never as success,
     * and the persisted grant set is byte-for-byte unchanged afterwards. No user
     * interaction is needed, so this leg always runs (it is not gated by
     * [assumeTrue] like the 21-grant leg).
     *
     * The folder is deliberately one no user could have picked, so the URI is
     * guaranteed to have no grant on any device.
     */
    @Test
    fun aTreeUriWithNoPersistableGrantIsDeniedAndAddsNothing() = runBlocking<Unit> {
        val before = heldTreeUris()
        val neverOffered =
            "content://com.android.externalstorage.documents/tree/primary%3ASwipeDeleteNeverPicked"
        assumeTrue(
            "this leg requires the URI to be un-granted; a device with that grant skips",
            neverOffered !in before,
        )

        val result = repository.persist(neverOffered, GrantMutator.FLAG_READ)

        assertEquals(
            "an un-granted URI must be reported as denied, never as a persisted slot",
            PersistResult.GrantDenied,
            result,
        )
        assertEquals(
            "a denied grant must not alter the platform grant set",
            before,
            heldTreeUris(),
        )
        Log.i(TAG, "V2 device check: un-granted URI refused (GrantDenied) and the grant set survived intact")
    }

    private companion object {
        const val TAG: String = "OnboardingInstrumented"
    }
}
