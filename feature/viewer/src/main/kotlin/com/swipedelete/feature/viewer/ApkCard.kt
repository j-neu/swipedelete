package com.swipedelete.feature.viewer

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.swipedelete.core.designsystem.SwipeDeleteDimens
import com.swipedelete.core.designsystem.SwipeDeleteTheme
import com.swipedelete.core.designsystem.components.SwipeDeleteIcons
import com.swipedelete.core.model.MediaItem
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The APK MIME. Detection also falls back to the `.apk` extension (Task 38). */
const val APK_MIME: String = "application/vnd.android.package-archive"

/** The installed package an APK is compared against, when it is visible. */
data class InstalledApp(val versionName: String?, val versionCode: Long)

/**
 * SPEC 5.4: "installed? which version?". Compared by version code, which is the
 * ordered value; the version name is shown only for the human.
 *
 * [UNKNOWN] is the honest outcome when package visibility could hide an
 * installed app: the card must not claim "not installed" when it simply cannot
 * see the package.
 */
enum class InstalledVersionStatus { NOT_INSTALLED, SAME, INSTALLED_OLDER, INSTALLED_NEWER, UNKNOWN }

fun installedVersionStatus(
    apkVersionCode: Long,
    installed: InstalledApp?,
    lookupRestricted: Boolean,
): InstalledVersionStatus = when {
    installed != null -> when {
        installed.versionCode == apkVersionCode -> InstalledVersionStatus.SAME
        installed.versionCode < apkVersionCode -> InstalledVersionStatus.INSTALLED_OLDER
        else -> InstalledVersionStatus.INSTALLED_NEWER
    }
    lookupRestricted -> InstalledVersionStatus.UNKNOWN
    else -> InstalledVersionStatus.NOT_INSTALLED
}

/** Everything the APK card renders, resolved from the file and the installed app. */
data class ApkCardData(
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val targetSdk: Int,
    val requestedPermissions: List<String>,
    val signatureSha256: String?,
    val label: String?,
    val icon: Bitmap?,
    val installed: InstalledApp?,
    val installedLookupRestricted: Boolean,
) {
    val installedStatus: InstalledVersionStatus
        get() = installedVersionStatus(versionCode, installed, installedLookupRestricted)
}

/** The outcome of reading an APK file: its card, or an honest failure. */
sealed interface ApkInspection {
    data class Loaded(val data: ApkCardData) : ApkInspection
    data class Failed(val mime: String) : ApkInspection
}

/**
 * Reads an APK's identity from the file and looks up the installed package.
 * Behind an interface so the card is testable and so the production reader's
 * visibility discipline (Task 38) stays in one place.
 */
interface ApkInspector {
    suspend fun inspect(item: MediaItem, open: () -> InputStream): ApkInspection
}

/**
 * SPEC 5.4: APK card — icon, package name, version, requested permissions,
 * signature, target SDK and an installed-version comparison.
 *
 * A package that cannot be read degrades to [UnsupportedCard] rather than a
 * blank frame.
 */
@Composable
fun ApkCard(
    item: MediaItem,
    openStream: (MediaItem) -> InputStream,
    onOpenWith: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    inspector: ApkInspector = rememberAndroidApkInspector(),
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    var inspection by remember(item.id) { mutableStateOf<ApkInspection?>(null) }

    LaunchedEffect(item.id) {
        inspection = withContext(ioDispatcher) { inspector.inspect(item) { openStream(item) } }
    }

    when (val result = inspection) {
        null -> ApkReading(item, modifier)

        is ApkInspection.Failed -> UnsupportedCard(
            item = item,
            reason = UnsupportedReason.UNDECODABLE,
            onOpenWith = onOpenWith,
            modifier = modifier,
        )

        is ApkInspection.Loaded -> ApkDetails(data = result.data, item = item, modifier = modifier)
    }
}

@Composable
private fun ApkReading(item: MediaItem, modifier: Modifier = Modifier) {
    val colors = SwipeDeleteTheme.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(SwipeDeleteDimens.SpacingMedium),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.viewer_apk_reading),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
        Text(
            text = item.path ?: item.uri,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun ApkDetails(data: ApkCardData, item: MediaItem, modifier: Modifier = Modifier) {
    val colors = SwipeDeleteTheme.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(SwipeDeleteDimens.SpacingMedium),
        verticalArrangement = Arrangement.spacedBy(SwipeDeleteDimens.SpacingSmall),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val icon = data.icon
            if (icon != null) {
                Image(
                    bitmap = icon.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                )
            } else {
                Icon(
                    imageVector = SwipeDeleteIcons.Empty,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                )
            }
            Column(modifier = Modifier.padding(start = SwipeDeleteDimens.SpacingSmall)) {
                Text(
                    text = data.label ?: item.path?.substringAfterLast('/') ?: item.uri,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onSurface,
                )
                Text(
                    text = stringResource(R.string.viewer_apk_package, data.packageName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
        }

        Text(
            text = stringResource(
                R.string.viewer_apk_version,
                data.versionName ?: "?",
                data.versionCode,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurface,
        )
        Text(
            text = stringResource(R.string.viewer_apk_target_sdk, data.targetSdk),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurface,
        )
        Text(
            text = stringResource(
                R.string.viewer_apk_signature,
                data.signatureSha256 ?: stringResource(R.string.viewer_apk_signature_unknown),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
        Text(
            text = installedVersionText(data),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurface,
        )

        Text(
            text = stringResource(R.string.viewer_apk_permissions, data.requestedPermissions.size),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurface,
        )
        data.requestedPermissions.forEach { permission ->
            Text(
                text = permission,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun installedVersionText(data: ApkCardData): String = when (data.installedStatus) {
    InstalledVersionStatus.NOT_INSTALLED -> stringResource(R.string.viewer_apk_installed_not)
    InstalledVersionStatus.UNKNOWN -> stringResource(R.string.viewer_apk_installed_unknown)
    InstalledVersionStatus.SAME ->
        stringResource(R.string.viewer_apk_installed_same, data.installed?.label().orEmpty())
    InstalledVersionStatus.INSTALLED_OLDER ->
        stringResource(R.string.viewer_apk_installed_older, data.installed?.label().orEmpty())
    InstalledVersionStatus.INSTALLED_NEWER ->
        stringResource(R.string.viewer_apk_installed_newer, data.installed?.label().orEmpty())
}

private fun InstalledApp?.label(): String = this?.versionName ?: this?.versionCode?.toString().orEmpty()

@Composable
fun rememberAndroidApkInspector(
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
): ApkInspector {
    val context = LocalContext.current
    return remember(context, ioDispatcher) { PackageManagerApkInspector(context, ioDispatcher) }
}

/**
 * The production reader.
 *
 * **Targeted package visibility only (Task 38, D7).** The APK's own identity is
 * parsed from the file with `PackageManager.getPackageArchiveInfo`, which needs
 * no package-visibility grant. The installed version is then queried for **that
 * one package name only** — never `getInstalledPackages`, never
 * `QUERY_ALL_PACKAGES`. When Android's package-visibility filtering hides the
 * installed app the lookup reports [InstalledVersionStatus.UNKNOWN] rather than
 * guessing.
 */
class PackageManagerApkInspector(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ApkInspector {

    override suspend fun inspect(item: MediaItem, open: () -> InputStream): ApkInspection =
        withContext(ioDispatcher) {
            val archive = runCatching { archiveFile(item, open) }.getOrNull()
                ?: return@withContext ApkInspection.Failed(item.mime.ifBlank { APK_MIME })
            try {
                readArchive(archive, item.mime.ifBlank { APK_MIME })
            } finally {
                if (archive.temporary) archive.file.delete()
            }
        }

    private fun readArchive(archive: Archive, mime: String): ApkInspection {
        val pm = context.packageManager
        val info = pm.getPackageArchiveInfo(archive.file.path, archiveFlags())
            ?: return ApkInspection.Failed(mime)
        val appInfo = info.applicationInfo ?: return ApkInspection.Failed(mime)
        // The archive parser does not set these; `loadIcon`/`loadLabel` need them.
        appInfo.sourceDir = archive.file.path
        appInfo.publicSourceDir = archive.file.path

        val (installed, lookupRestricted) = installedApp(pm, info.packageName)
        return ApkInspection.Loaded(
            ApkCardData(
                packageName = info.packageName,
                versionName = info.versionName,
                versionCode = info.versionCodeCompat(),
                targetSdk = appInfo.targetSdkVersion,
                requestedPermissions = info.requestedPermissions?.toList().orEmpty(),
                signatureSha256 = info.signatureSha256(),
                label = runCatching { appInfo.loadLabel(pm).toString() }.getOrNull(),
                icon = loadIcon(pm, appInfo),
                installed = installed,
                installedLookupRestricted = lookupRestricted,
            ),
        )
    }

    private fun archiveFlags(): Int =
        PackageManager.GET_PERMISSIONS or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }

    /** Queries exactly [packageName]; visibility filtering can deny it. */
    private fun installedApp(pm: PackageManager, packageName: String): Pair<InstalledApp?, Boolean> =
        try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
            InstalledApp(info.versionName, info.versionCodeCompat()) to false
        } catch (_: PackageManager.NameNotFoundException) {
            null to (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        }

    private fun PackageInfo.versionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            longVersionCode
        } else {
            @Suppress("DEPRECATION")
            versionCode.toLong()
        }

    private fun PackageInfo.signatureSha256(): String? {
        val signature = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            @Suppress("DEPRECATION")
            signatures?.firstOrNull()
        } ?: return null
        return MessageDigest.getInstance("SHA-256")
            .digest(signature.toByteArray())
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun loadIcon(pm: PackageManager, appInfo: ApplicationInfo): Bitmap? {
        val drawable: Drawable = runCatching { appInfo.loadIcon(pm) }.getOrNull() ?: return null
        val width = drawable.intrinsicWidth.takeIf { it > 0 }?.coerceAtMost(MAX_ICON_PX) ?: MAX_ICON_PX
        val height = drawable.intrinsicHeight.takeIf { it > 0 }?.coerceAtMost(MAX_ICON_PX) ?: MAX_ICON_PX
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    private fun archiveFile(item: MediaItem, open: () -> InputStream): Archive {
        val path = item.path
        if (!path.isNullOrBlank()) {
            val file = File(path)
            if (file.isFile) return Archive(file, temporary = false)
        }
        // A content:// APK has no filesystem path; the archive parser needs one.
        val temp = File.createTempFile("apk-inspect-", ".apk", context.cacheDir)
        open().use { input -> temp.outputStream().use { output -> input.copyTo(output) } }
        return Archive(temp, temporary = true)
    }

    private class Archive(val file: File, val temporary: Boolean)

    private companion object {
        const val MAX_ICON_PX = 256
    }
}
