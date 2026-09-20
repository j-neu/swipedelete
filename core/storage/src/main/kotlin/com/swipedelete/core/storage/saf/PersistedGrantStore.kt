package com.swipedelete.core.storage.saf

import com.swipedelete.core.model.SourceRef

/**
 * The persisted SAF tree grants the app currently holds. Spec §4.5: the five
 * destination grants plus the source grants *are* the permission model, so the
 * gateway only ever enumerates trees the user has explicitly granted.
 *
 * This is the smallest useful shape. Task 25 (M0-T16) implements it over the
 * real `ContentResolver.persistedUriPermissions` and DataStore; nothing in the
 * storage module depends on Room or DataStore for it.
 */
interface PersistedGrantStore {
    suspend fun sources(): List<SourceRef>
}
