package com.swipedelete.app.arch

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AC-14: nothing deletes permanently by accident. Outside `:core:storage`, the
 * only production code that may pass `allowPermanent = true` is the quarantine
 * controller, behind its typed-count confirmation, and no feature or app code
 * may call the gateway's `deletePermanently` at all.
 */
class PermanentDeleteScopeTest {

    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    private val outsideStorage: List<File> = root.walkTopDown()
        .onEnter { it.name != "build" && !it.name.startsWith(".") }
        .filter { it.isFile && it.extension == "kt" }
        .filter { it.invariantSeparatorsPath.contains("/src/main/") }
        .filter { !it.invariantSeparatorsPath.contains("/core/storage/") }
        .toList()

    @Test
    fun `only the quarantine controller grants permanent deletion`() {
        assertTrue(outsideStorage.size > 20, "the scan must actually see the production sources")

        val granters = outsideStorage
            .filter { Regex("""allowPermanent\s*=\s*true""").containsMatchIn(it.readText()) }
            .map { it.relativeTo(root).invariantSeparatorsPath }

        assertEquals(
            listOf("feature/dashboard/src/main/kotlin/com/swipedelete/feature/dashboard/quarantine/QuarantineController.kt"),
            granters,
        )
    }

    @Test
    fun `no feature or app code calls the gateway's permanent delete`() {
        val callers = outsideStorage
            .filter { it.readText().contains("deletePermanently(") }
            .map { it.relativeTo(root).invariantSeparatorsPath }

        assertEquals(emptyList(), callers)
    }
}
