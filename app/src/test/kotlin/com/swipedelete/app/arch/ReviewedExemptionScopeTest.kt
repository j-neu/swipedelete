package com.swipedelete.app.arch

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AC-7: `IndividuallyReviewed` lifts the protected-folder rules for items the
 * user decided one at a time. Only the deck may install it. A cleaner module or
 * any other rule-driven flow that did so would turn the exemption into a bulk
 * bypass, so this test pins the exact production files that construct it.
 */
class ReviewedExemptionScopeTest {

    private val allowed = setOf(
        "feature/deck/src/main/kotlin/com/swipedelete/feature/deck/domain/SlotMove.kt",
        "feature/deck/src/main/kotlin/com/swipedelete/feature/deck/session/GatewaySessionMutations.kt",
        "feature/deck/src/main/kotlin/com/swipedelete/feature/deck/session/SessionController.kt",
    )

    @Test
    fun `only the deck's session layer installs the reviewed exemption`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() }
        val sources = root.walkTopDown()
            .onEnter { it.name != "build" && !it.name.startsWith(".") }
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.invariantSeparatorsPath.contains("/src/main/") }
            .toList()
        assertTrue(sources.size > 50, "the scan must actually see the production sources")

        val installers = sources
            .filter { it.readText().contains("IndividuallyReviewed(") }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .filter { !it.endsWith("core/storage/IndividuallyReviewed.kt") }
            .toSet()

        assertEquals(allowed, installers)
    }
}
