package com.swipedelete.core.designsystem

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AC-11 seed (SPEC 6.4 / 12): "no destructive user-facing surface applies the
 * word 'cache' to a heuristic set". The enforcing gate is the CI resource-string
 * lint rule in M3-T11; this is the first pass.
 *
 * Scope of the AC-11 scan (honest limits):
 * - it enumerates every string resource **compiled into this module** (the fields
 *   of `R.string`), so it sees every locale variant that ships as a resource;
 * - it flags resources whose **name** contains one of the destructive markers
 *   `delete`, `destructive`, `remove`, `empty` (case-insensitive), i.e. a
 *   name-based heuristic over this module only;
 * - it checks only the word "cache" (case-insensitive) in their **values**;
 * - it does not see strings that live in other modules, in `:app`, or in code
 *   that is not a string resource (the M3-T11 lint rule is the cross-module gate).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResourceStringPolicyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Names of every string resource compiled into this module. */
    private val resourceNames: List<String> =
        R.string::class.java.declaredFields
            .filter { it.type == Int::class.javaPrimitiveType }
            .map { it.name }
            .sorted()

    @Test
    fun `no string resource applies the word cache to a destructive surface`() {
        val destructiveMarkers = listOf("delete", "destructive", "remove", "empty")
        val destructiveNames = resourceNames.filter { name ->
            destructiveMarkers.any { marker -> name.lowercase().contains(marker) }
        }

        assertTrue(
            "expected at least one destructive-named string resource; finding none would make this scan vacuous",
            destructiveNames.isNotEmpty(),
        )

        destructiveNames.forEach { name ->
            val value = value(name)
            assertFalse(
                "AC-11: destructive string resource '$name' must not apply the word \"cache\" to a " +
                    "heuristic set (value: \"$value\")",
                value.contains("cache", ignoreCase = true),
            )
        }
    }

    @Test
    fun `every string in the module is externalised`() {
        assertTrue(
            "the module must define at least one string resource",
            resourceNames.isNotEmpty(),
        )

        val sources = moduleMainKotlinSources()
        assertTrue("no Kotlin sources found under src/main/kotlin", sources.isNotEmpty())

        // A narrow source scan: user-facing literals passed to Compose Text or
        // set as a contentDescription are forbidden; those must come from
        // R.string. It deliberately does not attempt to find arbitrary literals.
        val offenders = mutableListOf<String>()
        val literalPatterns = listOf(
            Regex("""\bText\(\s*(?:text\s*=\s*)?"([^"]*)""""),
            Regex("""\bcontentDescription\s*=\s*"([^"]*)""""),
            Regex("""\btext\s*=\s*"([^"]*)""""),
        )
        sources.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                literalPatterns.forEach { pattern ->
                    pattern.findAll(line).forEach { match ->
                        val literal = match.groupValues[1]
                        if (literal.any { it.isLetter() }) {
                            offenders += "${file.name}:${index + 1} hardcoded user-facing literal \"$literal\""
                        }
                    }
                }
            }
        }
        assertTrue(
            "user-facing literals must be externalised to strings.xml; found: $offenders",
            offenders.isEmpty(),
        )

        // ...and the components must actually resolve their text from resources.
        val textSourcesWithoutResources = sources.filter { file ->
            val text = file.readText()
            text.contains("Text(") && !text.contains("R.string.")
        }
        assertTrue(
            "every source that renders Text must resolve it from R.string; offenders: " +
                textSourcesWithoutResources.map { it.name },
            textSourcesWithoutResources.isEmpty(),
        )
    }

    private fun value(name: String): String {
        val id = context.resources.getIdentifier(name, "string", context.packageName)
        assertTrue(
            "resource '$name' exists in R.string but resources.getIdentifier returned 0 " +
                "(packageName=${context.packageName}); the lookup must fail loudly, not silently pass",
            id != 0,
        )
        return context.getString(id)
    }

    private fun moduleMainKotlinSources(): List<File> {
        val direct = File("src/main/kotlin")
        val root = if (direct.isDirectory) {
            direct
        } else {
            var directory: File? = File("").absoluteFile
            var found: File? = null
            while (directory != null && found == null) {
                val candidate = File(directory, "core/designsystem/src/main/kotlin")
                if (candidate.isDirectory) found = candidate
                directory = directory.parentFile
            }
            found ?: error("core/designsystem/src/main/kotlin not found from ${File("").absolutePath}")
        }
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }
}
