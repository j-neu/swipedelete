package com.swipedelete.app.permissions

import androidx.annotation.StringRes
import com.swipedelete.core.model.Tier

/**
 * Result of an in-context capability request (SPEC 4.4). Requests never happen at
 * onboarding; the UI asks for exactly the capability the tapped feature needs.
 */
sealed interface TierResult {
    /** The requested capability is already held (or was granted). */
    data object Granted : TierResult

    /**
     * The request could not be satisfied right now. [rationaleRes] is shown
     * in-context so the panel can name what stays excluded.
     */
    data class Declined(@param:StringRes val rationaleRes: Int) : TierResult

    /**
     * The running build has the All Files Access capability compiled out
     * (`-PnoAllFilesAccess`); nothing was requested.
     */
    data object UnavailableInThisBuild : TierResult
}

/**
 * Reads the current capability tier and performs in-context permission requests.
 */
interface CapabilityController {
    /** Emits the current tier; re-reads platform permission state on collection. */
    fun tier(): kotlinx.coroutines.flow.Flow<Tier>

    /** Requests the photo/video read capability in context. */
    suspend fun requestPhotoVideo(): TierResult

    /** Requests All Files Access in context, or reports it unavailable in this build. */
    suspend fun requestAllFilesAccess(): TierResult
}
