package com.swipedelete.core.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The destructive container tokens must be reachable from the theme, not
 * dangling: Material's `errorContainer` / `onErrorContainer` roles carry them in
 * both colour schemes, so a destructive *surface* can be styled from the same
 * semantic source as the destructive action.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThemeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `light theme maps the destructive container tokens into the material scheme`() {
        var tokens: SwipeDeleteColors? = null
        var scheme: ColorScheme? = null
        composeRule.setContent {
            SwipeDeleteTheme(darkTheme = false) {
                tokens = SwipeDeleteTheme.colors
                scheme = MaterialTheme.colorScheme
            }
        }

        assertEquals(SwipeDeleteLightColors.destructiveContainer, tokens?.destructiveContainer)
        assertEquals(SwipeDeleteLightColors.onDestructiveContainer, tokens?.onDestructiveContainer)
        assertEquals(SwipeDeleteLightColors.destructiveContainer, scheme?.errorContainer)
        assertEquals(SwipeDeleteLightColors.onDestructiveContainer, scheme?.onErrorContainer)
    }

    @Test
    fun `dark theme maps the destructive container tokens into the material scheme`() {
        var tokens: SwipeDeleteColors? = null
        var scheme: ColorScheme? = null
        composeRule.setContent {
            SwipeDeleteTheme(darkTheme = true) {
                tokens = SwipeDeleteTheme.colors
                scheme = MaterialTheme.colorScheme
            }
        }

        assertEquals(SwipeDeleteDarkColors.destructiveContainer, tokens?.destructiveContainer)
        assertEquals(SwipeDeleteDarkColors.onDestructiveContainer, tokens?.onDestructiveContainer)
        assertEquals(SwipeDeleteDarkColors.destructiveContainer, scheme?.errorContainer)
        assertEquals(SwipeDeleteDarkColors.onDestructiveContainer, scheme?.onErrorContainer)
    }
}
