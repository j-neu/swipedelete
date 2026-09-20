package com.swipedelete.app.permissions

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.swipedelete.core.model.Tier
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Capability tiers (SPEC 4.4): tier 0 is the default on first run, tier 1 arrives
 * with the media read grant, tier 2 with All Files Access. Reads are pure state
 * reads — a tier transition must never delete, move or otherwise mutate user data.
 *
 * The request tests cover all three permission bands: the legacy band (API < 29,
 * which needs WRITE_EXTERNAL_STORAGE for a MediaStore quarantine to complete per
 * V4), the API 29-32 band (READ_EXTERNAL_STORAGE only), and API 33+.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CapabilityControllerTest {

    private lateinit var application: Application
    private lateinit var context: Context
    private var mediaRequests = 0
    private var settingsOpens = 0

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        context = application
        mediaRequests = 0
        settingsOpens = 0
    }

    private fun controller(
        buildHasAllFilesAccess: Boolean = true,
        allFilesAccessGranted: Boolean = false,
    ) = AndroidCapabilityController(
        context = context,
        buildHasAllFilesAccess = buildHasAllFilesAccess,
        allFilesAccessGranted = { allFilesAccessGranted },
        requestMediaPermissions = { mediaRequests++; true },
        openAllFilesAccessSettings = { settingsOpens++; true },
    )

    @Test
    fun `tier is tier 0 with no broad permissions held`() = runTest {
        assertEquals(Tier.TIER_0, controller().tier().first())
    }

    @Test
    fun `photo video grant moves tier 0 to tier 1`() = runTest {
        shadowOf(application).grantPermissions(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
        )

        val controller = controller()

        assertEquals(Tier.TIER_1, controller.tier().first())
        assertEquals(TierResult.Granted, controller.requestPhotoVideo())
        assertEquals(0, mediaRequests, "an already-held grant must not re-prompt")
    }

    @Test
    fun `all files access moves tier 1 to tier 2`() = runTest {
        shadowOf(application).grantPermissions(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
        )

        val controller = controller(buildHasAllFilesAccess = true, allFilesAccessGranted = true)

        assertEquals(Tier.TIER_2, controller.tier().first())
        assertEquals(TierResult.Granted, controller.requestAllFilesAccess())
        assertEquals(0, settingsOpens, "an already-held special access must not re-prompt")
    }

    @Test
    fun `revoking a grant moves the tier back down without data loss`() = runTest {
        val keeper = File(context.cacheDir, "user-file.txt").apply { writeText("untouched") }
        shadowOf(application).grantPermissions(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
        )
        val controller = controller()
        assertEquals(Tier.TIER_1, controller.tier().first())

        shadowOf(application).denyPermissions(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
        )

        assertEquals(Tier.TIER_0, controller.tier().first())
        assertTrue(keeper.isFile, "a tier change must not delete user files")
        assertEquals("untouched", keeper.readText(), "a tier change must not rewrite user files")
        assertEquals(0, mediaRequests, "reading the tier must not trigger a permission request")
        assertEquals(0, settingsOpens, "reading the tier must not open settings")
    }

    @Test
    fun `requestAllFilesAccess is unavailable in the stripped build`() = runTest {
        val controller = controller(buildHasAllFilesAccess = false, allFilesAccessGranted = false)

        assertEquals(TierResult.UnavailableInThisBuild, controller.requestAllFilesAccess())
        assertEquals(0, settingsOpens, "the stripped build must not request All Files Access")
        assertEquals(0, mediaRequests, "the stripped build must not request any permission")
    }

    @Test
    @Config(sdk = [28])
    fun `legacy band requests read and write external storage together`() = runTest {
        var requested: List<String> = emptyList()
        val controller = AndroidCapabilityController(
            context = context,
            buildHasAllFilesAccess = true,
            allFilesAccessGranted = { false },
            requestMediaPermissions = { requested = it.toList(); true },
        )

        assertEquals(TierResult.Granted, controller.requestPhotoVideo())
        assertEquals(
            2,
            requested.size,
            "API 26-28 needs WRITE_EXTERNAL_STORAGE as well as READ_EXTERNAL_STORAGE (V4): $requested",
        )
        assertEquals(
            setOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ),
            requested.toSet(),
        )
    }

    @Test
    @Config(sdk = [30])
    fun `pre-33 platform requests the legacy read permission only`() = runTest {
        var requested: List<String> = emptyList()
        val controller = AndroidCapabilityController(
            context = context,
            buildHasAllFilesAccess = true,
            allFilesAccessGranted = { false },
            requestMediaPermissions = { requested = it.toList(); true },
        )

        assertEquals(TierResult.Granted, controller.requestPhotoVideo())
        assertEquals(
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE),
            requested,
            "API 29-32 reads via READ_EXTERNAL_STORAGE; WRITE_EXTERNAL_STORAGE is a no-op at targetSdk 36",
        )
    }

    @Test
    @Config(sdk = [30])
    fun `legacy read grant moves tier 0 to tier 1`() = runTest {
        shadowOf(application).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)

        assertEquals(Tier.TIER_1, controller().tier().first())
    }

    @Test
    fun `images-only grant stays tier 0 because video is missing`() = runTest {
        // Partial-grant semantics: on API 33+ tier 1 requires BOTH media read
        // permissions, so a video deck is never told it is full with images-only.
        shadowOf(application).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES)

        assertEquals(Tier.TIER_0, controller().tier().first())
    }
}
