package com.swipedelete.app.permissions

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Guards the manifest half of the permission model (SPEC 4.1-4.4, AC-6):
 * - the amended permission set is declared exactly once, with the V4 legacy
 *   maxSdkVersion narrowing;
 * - no INTERNET permission exists anywhere (AC-6);
 * - the `-PnoAllFilesAccess` contingency manifest is the full manifest minus
 *   MANAGE_EXTERNAL_STORAGE and nothing else (no drift between the two copies,
 *   since the property swaps one manifest for the other).
 */
class AppManifestTest {

    private val fullManifest = manifest("app/src/main/AndroidManifest.xml")
    private val strippedManifest = manifest("app/src/noAllFilesAccess/AndroidManifest.xml")

    private val expectedPermissions = setOf(
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_MEDIA_VIDEO",
        "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.WRITE_EXTERNAL_STORAGE",
        "android.permission.MANAGE_EXTERNAL_STORAGE",
    )

    @Test
    fun `the full build declares exactly the amended permission set and no INTERNET`() {
        assertEquals(expectedPermissions, permissionNames(fullManifest))
        assertFalse(
            permissionNames(fullManifest).any { it.contains("INTERNET") },
            "AC-6: the app must never declare a network permission",
        )
    }

    @Test
    fun `the legacy permissions are narrowed with the V4 maxSdkVersion values`() {
        assertTrue(
            fullManifest.contains("""android:name="android.permission.READ_EXTERNAL_STORAGE"""") &&
                fullManifest.contains("""android:maxSdkVersion="32""""),
            "READ_EXTERNAL_STORAGE must carry android:maxSdkVersion=32 (V4)",
        )
        assertTrue(
            fullManifest.contains("""android:name="android.permission.WRITE_EXTERNAL_STORAGE"""") &&
                fullManifest.contains("""android:maxSdkVersion="29""""),
            "WRITE_EXTERNAL_STORAGE must carry android:maxSdkVersion=29 (V4)",
        )
    }

    @Test
    fun `the contingency manifest is the full manifest minus All Files Access`() {
        assertEquals(
            expectedPermissions - "android.permission.MANAGE_EXTERNAL_STORAGE",
            permissionNames(strippedManifest),
        )
        assertFalse(
            strippedManifest.contains("""android:name="android.permission.MANAGE_EXTERNAL_STORAGE""""),
            "the -PnoAllFilesAccess manifest must not declare All Files Access",
        )
        assertFalse(
            permissionNames(strippedManifest).any { it.contains("INTERNET") },
            "AC-6: the contingency build must never declare a network permission",
        )
    }

    private fun permissionNames(manifest: String): Set<String> {
        val document = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(manifest.byteInputStream())
        val nodes = document.getElementsByTagName("uses-permission")
        return buildSet {
            for (index in 0 until nodes.length) {
                val name = nodes.item(index).attributes?.getNamedItem("android:name")?.nodeValue
                if (name != null) add(name)
            }
        }
    }

    private fun manifest(relativePath: String): String {
        File(relativePath).takeIf { it.isFile }?.let { return it.readText() }

        var directory: File? = File("").absoluteFile
        while (directory != null) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) return candidate.readText()
            directory = directory.parentFile
        }
        error("$relativePath not found from ${File("").absolutePath}")
    }
}
