package com.swipedelete.app.permissions

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.swipedelete.core.model.Tier
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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
}
