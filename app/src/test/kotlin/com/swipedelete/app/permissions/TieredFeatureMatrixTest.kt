package com.swipedelete.app.permissions

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.swipedelete.app.R
import com.swipedelete.core.model.Tier
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AC-19: every feature works, in a narrower form, without any restricted
 * permission; the app never becomes unusable or shows a dead end when broad access
 * is declined, and each narrowed panel names what it excludes and offers an
 * in-context grant.
 *
 * Re-frozen semantics (the gate knows the build capability):
 * - `!buildHasAllFilesAccess && feature.requiresAllFilesAccess` ⇒ UNAVAILABLE_THIS_BUILD
 * - else tier satisfies the feature ⇒ FULL
 * - else ⇒ NARROWED(reasonRes, grantAffordanceRes)
 *
 * The tier mapping itself is pinned by an independent literal table below: the
 * matrix tests otherwise derive their expectations from the very enum they
 * exercise, so a wrong `fullAtTier` would sail through.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TieredFeatureMatrixTest {

    private val fullBuild = TieredFeatureGate(buildHasAllFilesAccess = true)
    private val strippedBuild = TieredFeatureGate(buildHasAllFilesAccess = false)
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `no feature is a dead end at tier 0 or tier 1 in the full build`() {
        for (tier in listOf(Tier.TIER_0, Tier.TIER_1)) {
            for (feature in Feature.entries) {
                val availability = fullBuild.isAvailable(feature, tier)
                assertTrue(
                    availability is Availability.FULL || availability is Availability.NARROWED,
                    "$feature is a dead end at $tier in the full build (AC-19): $availability",
                )
            }
        }
    }

    @Test
    fun `in the stripped build only all-files-access features may be unavailable`() {
        for (tier in Tier.entries) {
            for (feature in Feature.entries) {
                val availability = strippedBuild.isAvailable(feature, tier)
                if (feature.requiresAllFilesAccess) {
                    assertEquals(
                        Availability.UNAVAILABLE_THIS_BUILD,
                        availability,
                        "$feature requires All Files Access, so it must be unavailable in the stripped build",
                    )
                } else {
                    assertTrue(
                        availability is Availability.FULL || availability is Availability.NARROWED,
                        "$feature does not require All Files Access but is $availability in the stripped " +
                            "build at $tier (AC-19)",
                    )
                }
            }
        }
    }

    @Test
    fun `every narrowed panel names what it excludes and offers an in-context grant`() {
        for (tier in listOf(Tier.TIER_0, Tier.TIER_1)) {
            for (feature in Feature.entries) {
                val availability = fullBuild.isAvailable(feature, tier)
                if (availability !is Availability.NARROWED) continue

                assertNotEquals(0, availability.reasonRes, "$feature narrowed at $tier without a reason string")
                assertNotEquals(
                    0,
                    availability.grantAffordanceRes,
                    "$feature narrowed at $tier without a grant affordance string",
                )
                assertTrue(
                    context.getString(availability.reasonRes).isNotBlank(),
                    "$feature reason string resolves to blank",
                )
                assertTrue(
                    context.getString(availability.grantAffordanceRes).isNotBlank(),
                    "$feature grant affordance string resolves to blank",
                )
            }
        }
    }

    @Test
    fun `browsing viewing and triage never require all files access`() {
        listOf(Feature.Viewer, Feature.DeckTriage, Feature.SlotMove).forEach { feature ->
            assertFalse(
                feature.requiresAllFilesAccess,
                "${feature.name} must never require All Files Access (SPEC 4.2 condition 3)",
            )
            assertEquals(
                Availability.FULL,
                fullBuild.isAvailable(feature, Tier.TIER_0),
                "${feature.name} must work at tier 0 without any broad permission",
            )
        }
    }

    @Test
    fun `whole-library media decks are narrowed at tier 0 and full at tier 1`() {
        val atTier0 = fullBuild.isAvailable(Feature.WholeLibraryDecks, Tier.TIER_0)
        val narrowed = atTier0 as? Availability.NARROWED
        assertNotNull(narrowed, "SPEC 4.4 tier-1 whole-library decks must narrow at tier 0, was $atTier0")
        assertEquals(
            R.string.feature_narrow_affordance_photo_video,
            narrowed.grantAffordanceRes,
            "the tier-0 whole-library-deck narrowing must offer the photo/video grant (SPEC 4.4)",
        )
        assertEquals(
            Availability.FULL,
            fullBuild.isAvailable(Feature.WholeLibraryDecks, Tier.TIER_1),
            "SPEC 4.4 tier 1 is exactly the whole-library media decks",
        )
    }

    @Test
    fun `the tier mapping matches the SPEC 4-4 and 8 tables`() {
        // Literal expectations derived from docs/SPEC.md 4.4 (tier table) and
        // docs/SPEC.md 8 (module table), independent of the enum under test.
        val expectedFullAtTier = mapOf(
            Feature.DeckTriage to Tier.TIER_0,
            Feature.WholeLibraryDecks to Tier.TIER_1,
            Feature.SlotMove to Tier.TIER_0,
            Feature.Viewer to Tier.TIER_0,
            Feature.QuarantineRestore to Tier.TIER_0,
            Feature.JournalHistoryExport to Tier.TIER_0,
            Feature.Dashboard to Tier.TIER_0,
            Feature.AppCacheMeasure to Tier.TIER_0,
            Feature.Compression to Tier.TIER_0,
            Feature.Trends to Tier.TIER_0,
            Feature.LargeFiles to Tier.TIER_0,
            Feature.ExactDuplicates to Tier.TIER_0,
            Feature.SimilarMedia to Tier.TIER_1,
            Feature.ApkInstallers to Tier.TIER_1,
            Feature.EmptyFolders to Tier.TIER_2,
            Feature.PartialDownloads to Tier.TIER_2,
            Feature.ZeroByteTemp to Tier.TIER_2,
            Feature.ThumbnailCache to Tier.TIER_2,
            Feature.OrphanedAppDirs to Tier.TIER_2,
            Feature.ResidualFiles to Tier.TIER_2,
            Feature.CrashLogs to Tier.TIER_2,
        )

        assertEquals(
            expectedFullAtTier.keys,
            Feature.entries.toSet(),
            "the pinned tier table must cover every Feature exactly",
        )
        expectedFullAtTier.forEach { (feature, tier) ->
            assertEquals(tier, feature.fullAtTier, "${feature.name} has the wrong fullAtTier (SPEC 4.4/8)")
        }
    }

    @Test
    fun `the all-files-access feature set is pinned`() {
        val expected = setOf(
            Feature.ThumbnailCache,
            Feature.OrphanedAppDirs,
            Feature.ResidualFiles,
            Feature.CrashLogs,
        )
        assertEquals(
            expected,
            Feature.entries.filter { it.requiresAllFilesAccess }.toSet(),
            "only the SPEC 8 modules marked impossible at tier 0-1 may require All Files Access",
        )
    }

    @Test
    fun `only features that can narrow carry grant copy`() {
        Feature.entries.forEach { feature ->
            if (feature.fullAtTier == Tier.TIER_0) {
                assertNull(
                    feature.reasonRes,
                    "${feature.name} can never narrow, so it must not carry exclusion copy",
                )
                assertNull(
                    feature.grantAffordanceRes,
                    "${feature.name} can never narrow, so it must not carry a grant affordance " +
                        "(SPEC 4.2 condition 3: no All Files Access grant on triage/viewing)",
                )
            } else {
                assertNotNull(feature.reasonRes, "${feature.name} can narrow, so it must name what it excludes")
                assertNotNull(
                    feature.grantAffordanceRes,
                    "${feature.name} can narrow, so it must offer an in-context grant",
                )
            }
        }
    }
}
