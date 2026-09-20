package com.swipedelete.core.storage.saf

/**
 * Single source of truth for the instrumented SAF test's provider wiring.
 *
 * The androidTest manifest cannot reference Kotlin, so it declares the authority
 * as the literal `${applicationId}` plus [AUTHORITY_SUFFIX]; that suffix here is
 * what both the manifest and the runtime resolver must agree on.
 * `SafAndroidTestWiringTest` (host JVM) reads the manifest and asserts the
 * declaration matches these constants, so a mismatch is caught without a device.
 *
 * Lives in `testFixtures` because both the host test source set and the
 * androidTest source set depend on it.
 */
object TestDocumentsContract {

    /** Authority suffix the resolver searches for and the androidTest manifest declares. */
    const val AUTHORITY_SUFFIX: String = ".documents"

    /** Provider class declared in the androidTest manifest. */
    const val PROVIDER_CLASS: String = "com.swipedelete.core.storage.saf.TestDocumentsProvider"

    /** Action every `DocumentsProvider` advertises. */
    const val ACTION_DOCUMENTS_PROVIDER: String = "android.content.action.DOCUMENTS_PROVIDER"
}
