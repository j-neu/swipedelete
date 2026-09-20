package com.swipedelete.app.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import com.swipedelete.app.BuildConfig
import com.swipedelete.app.R
import com.swipedelete.core.model.Tier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The Android backing of [CapabilityController].
 *
 * Reads are pure: [tier] re-reads platform permission state and never mutates
 * anything. Requests are funnelled through the two injected seams so the
 * controller has no hidden side effects, and so the stripped build can be proven
 * to request nothing:
 *
 * - [requestMediaPermissions] performs the runtime photo/video request.
 * - [openAllFilesAccessSettings] opens the All Files Access special-access screen.
 *
 * [allFilesAccessGranted] defaults to the real `Environment.isExternalStorageManager()`
 * check and is injectable so the tier logic is testable without the platform toggle.
 */
class AndroidCapabilityController(
    private val context: Context,
    private val buildHasAllFilesAccess: Boolean = BuildConfig.ALL_FILES_ACCESS,
    private val allFilesAccessGranted: () -> Boolean = ::systemAllFilesAccessGranted,
    private val requestMediaPermissions: (suspend (Array<String>) -> Boolean)? = null,
    private val openAllFilesAccessSettings: (suspend () -> Boolean)? = null,
) : CapabilityController {

    override fun tier(): Flow<Tier> = flow { emit(currentTier()) }

    override suspend fun requestPhotoVideo(): TierResult {
        if (hasMediaRead()) return TierResult.Granted
        val requester = requestMediaPermissions
        if (requester != null && requester(mediaReadPermissions())) return TierResult.Granted
        return TierResult.Declined(R.string.permission_rationale_photo_video)
    }

    override suspend fun requestAllFilesAccess(): TierResult {
        if (!buildHasAllFilesAccess) return TierResult.UnavailableInThisBuild
        if (hasAllFilesAccess()) return TierResult.Granted
        val opener = openAllFilesAccessSettings
        if (opener != null && opener()) return TierResult.Granted
        return TierResult.Declined(R.string.permission_rationale_all_files_access)
    }

    private fun currentTier(): Tier = when {
        hasMediaRead() && hasAllFilesAccess() -> Tier.TIER_2
        hasMediaRead() -> Tier.TIER_1
        else -> Tier.TIER_0
    }

    private fun hasAllFilesAccess(): Boolean = buildHasAllFilesAccess && allFilesAccessGranted()

    private fun hasMediaRead(): Boolean =
        mediaReadPermissions().any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun mediaReadPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
}

private fun systemAllFilesAccessGranted(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()
