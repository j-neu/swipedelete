package com.swipedelete.feature.viewer

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.swipedelete.core.designsystem.SwipeDeleteTheme
import com.swipedelete.core.model.ItemId
import com.swipedelete.core.model.MediaItem
import com.swipedelete.core.model.SourceId
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SPEC 5.4 (APK): icon, package name, version, requested permissions, signature,
 * target SDK and an installed-version comparison. Task 38 delivers the card and
 * the routing; the *installed* lookup uses targeted `PackageManager` visibility
 * only (no `QUERY_ALL_PACKAGES`), so the production reader is behind
 * [ApkInspector] and these tests drive the card against a fake.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewerApkTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `an apk is routed to the apk card by mime and by extension`() {
        assertEquals(
            ViewerContentKind.APK,
            viewerContentKind("application/vnd.android.package-archive"),
        )
        assertEquals(
            ViewerContentKind.APK,
            viewerContentKind(apkItem(mime = "application/octet-stream")),
            "an .apk file whose MIME is wrong must still be read as a package, not a document",
        )
        assertEquals(ViewerContentKind.DOCUMENT, viewerContentKind("application/pdf"))
        assertEquals(ViewerContentKind.DOCUMENT, viewerContentKind("application/octet-stream"))
    }

    @Test
    fun `the installed-version comparison orders by version code`() {
        assertEquals(
            InstalledVersionStatus.INSTALLED_OLDER,
            installedVersionStatus(apkVersionCode = 5, installed = InstalledApp("1.0", 4), lookupRestricted = false),
        )
        assertEquals(
            InstalledVersionStatus.INSTALLED_NEWER,
            installedVersionStatus(apkVersionCode = 5, installed = InstalledApp("1.1", 6), lookupRestricted = false),
        )
        assertEquals(
            InstalledVersionStatus.SAME,
            installedVersionStatus(apkVersionCode = 5, installed = InstalledApp("1.0", 5), lookupRestricted = false),
        )
        assertEquals(
            InstalledVersionStatus.NOT_INSTALLED,
            installedVersionStatus(apkVersionCode = 5, installed = null, lookupRestricted = false),
        )
        assertEquals(
            InstalledVersionStatus.UNKNOWN,
            installedVersionStatus(apkVersionCode = 5, installed = null, lookupRestricted = true),
            "package visibility can hide an installed app; the card must say so rather than claim 'not installed'",
        )
    }

    @Test
    fun `the apk card shows package, version, target sdk, signature and permissions`() {
        showCard(ApkInspection.Loaded(cardData()))

        composeRule.onNodeWithText("com.example.demo", substring = true).assertExists()
        composeRule.onNodeWithText("Version: 1.2.3 (123)").assertExists()
        composeRule.onNodeWithText("Target SDK: 33", substring = true).assertExists()
        composeRule.onNodeWithText("abc123", substring = true).assertExists()
        composeRule.onNodeWithText("android.permission.CAMERA", substring = true).assertExists()
    }

    @Test
    fun `the apk card shows the installed-version comparison`() {
        showCard(ApkInspection.Loaded(cardData(installed = InstalledApp("1.0.0", 4))))

        composeRule.onNodeWithText("older", substring = true).assertExists()
    }

    @Test
    fun `the apk card reports the same installed version honestly`() {
        showCard(ApkInspection.Loaded(cardData(installed = InstalledApp("1.2.3", 123))))

        composeRule.onNodeWithText("same version", substring = true).assertExists()
    }

    @Test
    fun `an unreadable package names its mime and never shows a blank frame`() {
        showCard(ApkInspection.Failed("application/vnd.android.package-archive"))

        composeRule.onNodeWithText("application/vnd.android.package-archive", substring = true).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.viewer_action_open_with)).assertExists()
    }

    private fun showCard(result: ApkInspection) {
        composeRule.setContent {
            SwipeDeleteTheme {
                ApkCard(
                    item = apkItem(),
                    openStream = { ByteArrayInputStream(ByteArray(0)) },
                    onOpenWith = {},
                    inspector = FakeApkInspector(result),
                    ioDispatcher = Dispatchers.Unconfined,
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun cardData(
        installed: InstalledApp? = null,
        restricted: Boolean = false,
    ): ApkCardData = ApkCardData(
        packageName = "com.example.demo",
        versionName = "1.2.3",
        versionCode = 123,
        targetSdk = 33,
        requestedPermissions = listOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO"),
        signatureSha256 = "abc123",
        label = "Demo",
        icon = null,
        installed = installed,
        installedLookupRestricted = restricted,
    )

    private fun apkItem(mime: String = "application/vnd.android.package-archive"): MediaItem = MediaItem(
        id = ItemId("file:/Download/demo.apk"),
        uri = "content://media/external/file/9",
        path = "/Download/demo.apk",
        relativePath = "Download",
        mediaStoreId = 9L,
        mime = mime,
        sizeBytes = 5_000_000L,
        width = null,
        height = null,
        durationMs = null,
        dateAdded = 0L,
        dateModified = 0L,
        bucket = "Download",
        sourceId = SourceId("tree:/Download"),
    )

    private class FakeApkInspector(private val result: ApkInspection) : ApkInspector {
        override suspend fun inspect(item: MediaItem, open: () -> InputStream): ApkInspection = result
    }
}
