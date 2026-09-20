package com.swipedelete.app.permissions

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import org.junit.Test
import org.w3c.dom.Element

/**
 * Guards the manifest half of the permission model (SPEC 4.1-4.4, AC-6):
 * - the amended permission set is declared exactly once, with the V4 legacy
 *   maxSdkVersion narrowing on the matching element;
 * - no INTERNET permission exists anywhere (AC-6);
 * - the `-PnoAllFilesAccess` contingency manifest is the full manifest minus the
 *   MANAGE_EXTERNAL_STORAGE permission block and nothing else.
 *
 * The contingency guard compares the two documents' **full element and attribute
 * trees** (application attributes, components, everything), not just permission
 * names. The property replaces `src/main/AndroidManifest.xml` with the contingency
 * file, so without a tree comparison a component added to `main` would silently
 * vanish from the stripped build with every test green.
 */
class AppManifestTest {

    private val fullTree = parse(manifest("app/src/main/AndroidManifest.xml"))
    private val strippedTree = parse(manifest("app/src/noAllFilesAccess/AndroidManifest.xml"))

    private val allFilesAccessPermission = "android.permission.MANAGE_EXTERNAL_STORAGE"

    private val expectedPermissions = setOf(
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_MEDIA_VIDEO",
        "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.WRITE_EXTERNAL_STORAGE",
        "android.permission.MANAGE_EXTERNAL_STORAGE",
    )

    @Test
    fun `the full build declares exactly the amended permission set and no INTERNET`() {
        assertEquals(expectedPermissions, permissionNames(fullTree))
        assertFalse(
            permissionNames(fullTree).any { it.contains("INTERNET") },
            "AC-6: the app must never declare a network permission",
        )
    }

    @Test
    fun `the legacy permissions carry the V4 maxSdkVersion on their own element`() {
        assertEquals(
            "32",
            maxSdkVersionOf(fullTree, "android.permission.READ_EXTERNAL_STORAGE"),
            "READ_EXTERNAL_STORAGE must carry android:maxSdkVersion=32 (V4)",
        )
        assertEquals(
            "29",
            maxSdkVersionOf(fullTree, "android.permission.WRITE_EXTERNAL_STORAGE"),
            "WRITE_EXTERNAL_STORAGE must carry android:maxSdkVersion=29 (V4)",
        )
        assertNull(
            maxSdkVersionOf(fullTree, "android.permission.READ_MEDIA_IMAGES"),
            "READ_MEDIA_IMAGES must not be narrowed",
        )
        assertNull(
            maxSdkVersionOf(fullTree, "android.permission.READ_MEDIA_VIDEO"),
            "READ_MEDIA_VIDEO must not be narrowed",
        )
        assertNull(
            maxSdkVersionOf(fullTree, allFilesAccessPermission),
            "MANAGE_EXTERNAL_STORAGE must not be narrowed",
        )
    }

    @Test
    fun `the contingency manifest is the full manifest minus All Files Access`() {
        assertEquals(
            withoutPermission(fullTree, allFilesAccessPermission),
            strippedTree,
            "the -PnoAllFilesAccess manifest must be the full manifest minus the All Files Access permission " +
                "element, with every other element and attribute identical",
        )
        assertFalse(
            permissionNames(strippedTree).any { it.contains("INTERNET") },
            "AC-6: the contingency build must never declare a network permission",
        )
    }

    /** An element tree with namespace declarations excluded and attributes keyed by namespace. */
    private data class XmlNode(
        val name: String,
        val attributes: Map<String, String>,
        val children: List<XmlNode>,
    ) {
        fun attribute(androidName: String): String? = attributes["$ANDROID_NS|$androidName"]
    }

    private fun permissionNames(node: XmlNode): Set<String> =
        node.children
            .filter { it.name == "uses-permission" }
            .mapNotNull { it.attribute("name") }
            .toSet()

    private fun maxSdkVersionOf(node: XmlNode, permission: String): String? =
        node.children
            .firstOrNull { it.name == "uses-permission" && it.attribute("name") == permission }
            ?.attribute("maxSdkVersion")

    private fun withoutPermission(node: XmlNode, permission: String): XmlNode = node.copy(
        children = node.children.mapNotNull { child ->
            when {
                child.name == "uses-permission" && child.attribute("name") == permission -> null
                else -> withoutPermission(child, permission)
            }
        },
    )

    private fun parse(xml: String): XmlNode {
        val document = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(xml.byteInputStream())
        return toNode(document.documentElement)
    }

    private fun toNode(element: Element): XmlNode {
        val attributes = buildMap {
            for (index in 0 until element.attributes.length) {
                val attribute = element.attributes.item(index)
                if (attribute.namespaceURI == XMLNS_NS) continue
                val name = attribute.localName ?: attribute.nodeName
                put("${attribute.namespaceURI ?: ""}|$name", attribute.nodeValue)
            }
        }
        val children = buildList {
            val childNodes = element.childNodes
            for (index in 0 until childNodes.length) {
                val child = childNodes.item(index)
                if (child.nodeType == org.w3c.dom.Node.ELEMENT_NODE) add(toNode(child as Element))
            }
        }
        return XmlNode(element.localName ?: element.nodeName, attributes, children)
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

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        const val XMLNS_NS = "http://www.w3.org/2000/xmlns/"
    }
}
