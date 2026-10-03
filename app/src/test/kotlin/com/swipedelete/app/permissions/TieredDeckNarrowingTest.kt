package com.swipedelete.app.permissions

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.swipedelete.app.R
import com.swipedelete.core.designsystem.SwipeDeleteTheme
import com.swipedelete.core.model.Tier
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * SPEC 4.4 / AC-19, decision D2: the deck's whole-library affordance is a
 * *narrowed* form at tier 0 — never unavailable — and it asks the gate about
 * [Feature.WholeLibraryDecks], not [Feature.DeckTriage] (triaging a granted
 * folder is already full at tier 0). Tapping the affordance requests the
 * photo/video capability in context and nothing else.
 *
 * The tier reads are pure: a permission grant moves tier 0 → tier 1 without
 * deleting, moving or rewriting a single user file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TieredDeckNarrowingTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var application: Application
    private lateinit var context: Context

    private val gate = TieredFeatureGate(buildHasAllFilesAccess = true)

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        context = application
    }

    @Test
    fun `at tier 0 the whole-library decks are narrowed not unavailable`() {
        val availability = gate.isAvailable(Feature.WholeLibraryDecks, Tier.TIER_0)

        val narrowed = availability as? Availability.NARROWED
        assertTrue(
            narrowed != null,
            "the whole-library affordance must narrow at tier 0, never be a dead end (AC-19): $availability",
        )
        assertEquals(
            R.string.feature_narrow_affordance_photo_video,
            narrowed.grantAffordanceRes,
            "the narrowing must offer the photo/video grant in context",
        )
        assertEquals(
            R.string.feature_narrow_reason_media_library,
            narrowed.reasonRes,
            "the narrowing must name what it excludes",
        )
    }

    @Test
    fun `the deck triage feature is full at tier 0 so the affordance must query whole library decks`() {
        assertEquals(
            Availability.FULL,
            gate.isAvailable(Feature.DeckTriage, Tier.TIER_0),
            "granted-folder triage is full at tier 0; the narrowing belongs to WholeLibraryDecks",
        )
    }

    @Test
    fun `requesting photo video moves tier 0 to tier 1 without touching storage`() = runTest {
        val keeper = File(context.cacheDir, "tier-keeper.txt").apply { writeText("untouched") }
        val controller = AndroidCapabilityController(
            context = context,
            buildHasAllFilesAccess = true,
            allFilesAccessGranted = { false },
            requestMediaPermissions = { permissions ->
                shadowOf(application).grantPermissions(*permissions)
                true
            },
        )

        assertEquals(Tier.TIER_0, controller.tier().first())
        assertEquals(TierResult.Granted, controller.requestPhotoVideo())
        assertEquals(Tier.TIER_1, controller.tier().first(), "the media grant is exactly tier 1")

        assertTrue(keeper.isFile, "a tier change must not delete user files")
        assertEquals("untouched", keeper.readText(), "a tier change must not rewrite user files")
    }

    @Test
    fun `the narrowed affordance shows its reason and offers the in-context grant`() {
        val controller = FakeCapabilityController(Tier.TIER_0)
        composeRule.setContent {
            SwipeDeleteTheme {
                WholeLibraryAffordance(controller = controller, gate = gate)
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.feature_narrow_reason_media_library))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.feature_narrow_affordance_photo_video))
            .assertIsDisplayed()
            .performClick()
        composeRule.waitForIdle()

        assertEquals(1, controller.photoVideoRequests, "tapping the affordance must request in context")
        assertEquals(0, controller.allFilesRequests, "the deck affordance must not request All Files Access")
    }
}

/**
 * A controller whose tier flips on a successful request, so the affordance's
 * refresh path is exercised without the platform permission dialogs.
 */
private class FakeCapabilityController(initialTier: Tier) : CapabilityController {
    private val state = MutableStateFlow(initialTier)

    var photoVideoRequests: Int = 0
    var allFilesRequests: Int = 0

    override fun tier(): Flow<Tier> = state

    override suspend fun requestPhotoVideo(): TierResult {
        photoVideoRequests++
        state.value = Tier.TIER_1
        return TierResult.Granted
    }

    override suspend fun requestAllFilesAccess(): TierResult {
        allFilesRequests++
        return TierResult.Granted
    }
}
