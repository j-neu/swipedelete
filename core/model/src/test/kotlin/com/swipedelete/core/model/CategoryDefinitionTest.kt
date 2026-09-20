package com.swipedelete.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CategoryDefinitionTest {
    @Test
    fun `media-eligible categories match spec table 6 4`() {
        val expected = setOf(
            Category.LARGE_FILE, Category.EXACT_DUPLICATE, Category.SIMILAR_MEDIA, Category.OBSOLETE_MEDIA,
        )
        assertEquals(expected, Category.entries.filter { it.mediaEligible }.toSet())
    }

    @Test
    fun `no category that can contain media is default-on`() {
        Category.entries.filter { it.mediaEligible }
            .forEach { assertNotEquals(CategoryDefault.OFF, it.defaultState, "$it may not default off") }
    }

    @Test
    fun `the word cache never appears as an actable category default`() {
        assertEquals(CategoryDefault.MEASURE_ONLY, Category.APP_CACHE.defaultState)
    }
}
