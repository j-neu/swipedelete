package com.swipedelete.feature.settings.grants

import com.swipedelete.core.model.SourceRef

/**
 * The outcome of asking the platform for a persisted tree grant (SPEC 4.5).
 *
 * The grant set **is** the app's permission model, so a refused grant is never
 * reported as success:
 *
 * - [Persisted] — the platform now holds the grant and it is visible in
 *   `persistedUriPermissions`.
 * - [GrantCapReached] — the app's own cap ([SourceGrantRepository.MAX_GRANTS],
 *   V2). This is a product/UX limit, not the OS one: the platform cap is 512 on
 *   API 30+ / 128 on API 26-29, and **exceeding it silently evicts the oldest
 *   grant**. Rejecting at 20 keeps the existing set intact and observable.
 * - [GrantDenied] — the platform refused to convert the picker's transient
 *   grant into a persisted one (`takePersistableUriPermission` throws
 *   `SecurityException`; V2 requires treating that as "slot not granted", never
 *   as success).
 */
sealed interface PersistResult {

    /** The grant was persisted; [source] is how the rest of the app addresses it. */
    data class Persisted(val source: SourceRef) : PersistResult

    /** The app's [SourceGrantRepository.MAX_GRANTS] limit is already reached. */
    data object GrantCapReached : PersistResult

    /** The platform refused the grant; nothing was persisted. */
    data object GrantDenied : PersistResult
}
