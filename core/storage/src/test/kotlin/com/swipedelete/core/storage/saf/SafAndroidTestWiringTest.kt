package com.swipedelete.core.storage.saf

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Host-JVM check that the instrumented SAF test's *declared* wiring matches the
 * shared constants the runtime resolver uses.
 *
 * The androidTest manifest cannot reference Kotlin, so this reads the manifest
 * and asserts:
 * - it declares [TestDocumentsContract.PROVIDER_CLASS] with authority
 *   `<applicationId>[AUTHORITY_SUFFIX]` — the exact authority
 *   [TestDocumentsProvider.resolveAuthority] searches for;
 * - it advertises the `DocumentsProvider` action and is exported.
 *
 * This makes the provider/authority alignment checkable without a device; the
 * cache-directory alignment is single-sourced in
 * [TestDocumentsProvider.baseDir] (both the provider and the test call it), which
 * has no manifest half to disagree with.
 */
class SafAndroidTestWiringTest {

    @Test
    fun `androidTest manifest declares the provider and authority the resolver searches for`() {
        val manifest = androidTestManifest()

        assertTrue(
            manifest.contains(TestDocumentsContract.PROVIDER_CLASS),
            "androidTest manifest must declare ${TestDocumentsContract.PROVIDER_CLASS}",
        )
        assertTrue(
            manifest.contains("android:authorities=\"\${applicationId}${TestDocumentsContract.AUTHORITY_SUFFIX}\""),
            "androidTest manifest must declare authority " +
                "\"\${applicationId}${TestDocumentsContract.AUTHORITY_SUFFIX}\"",
        )
        assertTrue(
            manifest.contains(TestDocumentsContract.ACTION_DOCUMENTS_PROVIDER),
            "provider must advertise ${TestDocumentsContract.ACTION_DOCUMENTS_PROVIDER}",
        )
        assertTrue(
            manifest.contains("android:exported=\"true\""),
            "DocumentsProvider must be exported on API 31+",
        )
    }

    @Test
    fun `the authority suffix is specific enough to identify the test provider`() {
        assertEquals(".documents", TestDocumentsContract.AUTHORITY_SUFFIX)
        assertTrue(TestDocumentsContract.AUTHORITY_SUFFIX.startsWith("."))
    }

    private fun androidTestManifest(): String {
        File("src/androidTest/AndroidManifest.xml").takeIf { it.isFile }?.let { return it.readText() }

        var directory: File? = File("").absoluteFile
        while (directory != null) {
            val candidate = File(directory, "core/storage/src/androidTest/AndroidManifest.xml")
            if (candidate.isFile) return candidate.readText()
            directory = directory.parentFile
        }
        error("androidTest AndroidManifest.xml not found from ${File("").absolutePath}")
    }
}
