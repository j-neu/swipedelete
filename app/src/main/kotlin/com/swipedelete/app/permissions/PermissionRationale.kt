package com.swipedelete.app.permissions

import androidx.annotation.StringRes
import com.swipedelete.app.R
import com.swipedelete.core.model.Tier

/**
 * What a feature can offer under the running build and the held tier.
 *
 * - [FULL]: the feature works as designed.
 * - [NARROWED]: a narrower form works; [reasonRes] names what is excluded and
 *   [grantAffordanceRes] offers the in-context grant that would widen it.
 * - [UNAVAILABLE_THIS_BUILD]: only reachable when the build itself has All Files
 *   Access compiled out and the feature requires it.
 */
sealed interface Availability {
    data object FULL : Availability

    data class NARROWED(
        @param:StringRes val reasonRes: Int,
        @param:StringRes val grantAffordanceRes: Int,
    ) : Availability

    data object UNAVAILABLE_THIS_BUILD : Availability
}

/**
 * Every capability in the SPEC 4.4 tier table and the SPEC 8 module table.
 *
 * [requiresAllFilesAccess] is true only for features that cannot exist without
 * All Files Access. No feature that lets the user browse, view or triage a file
 * may be in that set (SPEC 4.2 condition 3): [Viewer], [DeckTriage] and
 * [SlotMove] are deliberately false and full at tier 0.
 *
 * [fullAtTier] is the lowest tier at which the feature is not narrowed. It is
 * also the tier that the narrowed panel's grant affordance pushes the user to.
 */
enum class Feature(
    val requiresAllFilesAccess: Boolean,
    val fullAtTier: Tier,
    @param:StringRes val reasonRes: Int,
    @param:StringRes val grantAffordanceRes: Int,
) {
    // Tier 0: full triage inside granted folders, the five slots, viewer,
    // quarantine, journal, dashboard, compression and the granted-tree modules.
    DeckTriage(
        requiresAllFilesAccess = false,
        fullAtTier = Tier.TIER_0,
        reasonRes = R.string.feature_narrow_reason_scope_folders,
        grantAffordanceRes = R.string.feature_narrow_affordance_all_files,
    ),
    SlotMove(
        requiresAllFilesAccess = false,
        fullAtTier = Tier.TIER_0,
        reasonRes = R.string.feature_narrow_reason_scope_folders,
        grantAffordanceRes = R.string.feature_narrow_affordance_all_files,
    ),
    Viewer(
        requiresAllFilesAccess = false,
        fullAtTier = Tier.TIER_0,
        reasonRes = R.string.feature_narrow_reason_scope_folders,
        grantAffordanceRes = R.string.feature_narrow_affordance_all_files,
    ),
    QuarantineRestore(
        requiresAllFilesAccess = false,
        fullAtTier = Tier.TIER_0,
        reasonRes = R.string.feature_narrow_reason_scope_folders,
        grantAffordanceRes = R.string.feature_narrow_affordance_all_files,
    ),
    JournalHistoryExport(
        requiresAllFilesAccess = false,
        fullAtTier = Tier.TIER_0,
        reasonRes = R.string.feature_narrow_reason_scope_folders,
        grantAffordanceRes = R.string.feature_narrow_affordance_all_files,
    ),
    Dashboard(
        requiresAllFilesAccess = false,
        fullAtTier = Tier.TIER_0,
        reasonRes = R.string.feature_narrow_reason_scope_folders,
        grantAffordanceRes = R.string.feature_narrow_affordance_all_files,
    ),
    AppCacheMeasure(
        requiresAllFilesAccess = false,
        fullAtTier = Tier.TIER_0,
        reasonRes = R.string.feature_narrow_reason_scope_folders,
        grantAffordanceRes = R.string.feature_narrow_affordance_all_files,
    ),
    Compression(
        requiresAllFilesAccess = false,
        fullAtTier = Tier.TIER_0,
        reasonRes = R.string.feature_narrow_reason_scope_folders,
        grantAffordanceRes = R.string.feature_narrow_affordance_all_files,
    ),
    Trends(
        requiresAllFilesAccess = false,
        fullAtTier = Tier.TIER_0,
        reasonRes = R.string.feature_narrow_reason_scope_folders,
        grantAffordanceRes = R.string.feature_narrow_affordance_all_files,
    ),
    LargeFiles(
        requiresAllFilesAccess = false,
        fullAtTier = Tier.TIER_0,
        reasonRes = R.string.feature_narrow_reason_scope_folders,
        grantAffordanceRes = R.string.feature_narrow_affordance_all_files,
    ),
    ExactDuplicates(
        requiresAllFilesAccess = false,
        fullAtTier = Tier.TIER_0,
        reasonRes = R.string.feature_narrow_reason_scope_folders,
        grantAffordanceRes = R.string.feature_narrow_affordance_all_files,
    ),

    // Tier 1: whole-library media modules. Narrowed at tier 0 (granted folders only).
    SimilarMedia(
        requiresAllFilesAccess = false,
        fullAtTier = Tier.TIER_1,
        reasonRes = R.string.feature_narrow_reason_media_library,
        grantAffordanceRes = R.string.feature_narrow_affordance_photo_video,
    ),
    ApkInstallers(
        requiresAllFilesAccess = false,
        fullAtTier = Tier.TIER_1,
        reasonRes = R.string.feature_narrow_reason_media_library,
        grantAffordanceRes = R.string.feature_narrow_affordance_photo_video,
    ),

    // Tier 2, but SAF/MediaStore can still serve a narrower granted-trees form.
    EmptyFolders(
        requiresAllFilesAccess = false,
        fullAtTier = Tier.TIER_2,
        reasonRes = R.string.feature_narrow_reason_granted_trees,
        grantAffordanceRes = R.string.feature_narrow_affordance_all_files,
    ),
    PartialDownloads(
        requiresAllFilesAccess = false,
        fullAtTier = Tier.TIER_2,
        reasonRes = R.string.feature_narrow_reason_granted_trees,
        grantAffordanceRes = R.string.feature_narrow_affordance_all_files,
    ),
    ZeroByteTemp(
        requiresAllFilesAccess = false,
        fullAtTier = Tier.TIER_2,
        reasonRes = R.string.feature_narrow_reason_granted_trees,
        grantAffordanceRes = R.string.feature_narrow_affordance_all_files,
    ),

    // Non-media global management: impossible without All Files Access, and
    // compiled out entirely by the -PnoAllFilesAccess contingency build.
    ThumbnailCache(
        requiresAllFilesAccess = true,
        fullAtTier = Tier.TIER_2,
        reasonRes = R.string.feature_narrow_reason_non_media_global,
        grantAffordanceRes = R.string.feature_narrow_affordance_all_files,
    ),
    OrphanedAppDirs(
        requiresAllFilesAccess = true,
        fullAtTier = Tier.TIER_2,
        reasonRes = R.string.feature_narrow_reason_non_media_global,
        grantAffordanceRes = R.string.feature_narrow_affordance_all_files,
    ),
    ResidualFiles(
        requiresAllFilesAccess = true,
        fullAtTier = Tier.TIER_2,
        reasonRes = R.string.feature_narrow_reason_non_media_global,
        grantAffordanceRes = R.string.feature_narrow_affordance_all_files,
    ),
    CrashLogs(
        requiresAllFilesAccess = true,
        fullAtTier = Tier.TIER_2,
        reasonRes = R.string.feature_narrow_reason_non_media_global,
        grantAffordanceRes = R.string.feature_narrow_affordance_all_files,
    ),
}

/**
 * The AC-19 gate. It knows whether the running build carries the All Files Access
 * capability at all, so a stripped build reports [Availability.UNAVAILABLE_THIS_BUILD]
 * for all-files-access features while everything else still degrades to a narrower
 * form instead of a dead end.
 */
class TieredFeatureGate(private val buildHasAllFilesAccess: Boolean) {

    fun isAvailable(feature: Feature, tier: Tier): Availability {
        if (!buildHasAllFilesAccess && feature.requiresAllFilesAccess) {
            return Availability.UNAVAILABLE_THIS_BUILD
        }
        return if (tier.isAtLeast(feature.fullAtTier)) {
            Availability.FULL
        } else {
            Availability.NARROWED(feature.reasonRes, feature.grantAffordanceRes)
        }
    }
}
