package com.swipedelete.app.nav

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.testing.TestNavHostController
import androidx.test.core.app.ApplicationProvider
import com.swipedelete.core.designsystem.SwipeDeleteTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The M0 app shell must declare every route the plan names, even where only
 * `onboarding` and `settings` have a real screen yet (M1 owns the deck, M0-T16
 * owns onboarding): the routes are the shell's contract with later tasks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NavGraphTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `nav graph declares every destination`() {
        val navController = TestNavHostController(ApplicationProvider.getApplicationContext())
        navController.navigatorProvider.addNavigator(ComposeNavigator())

        composeTestRule.setContent {
            SwipeDeleteTheme {
                AppNavHost(navController = navController)
            }
        }
        composeTestRule.waitForIdle()

        val declared = listOf(
            "onboarding",
            "deck",
            "viewer/{itemId}",
            "dashboard",
            "quarantine",
            "history",
            "settings",
        )
        assertEquals(declared.toSet(), Destinations.all.toSet(), "Destinations must name exactly the M0 routes")

        declared.forEach { route ->
            assertNotNull(
                navController.graph.findNode(route),
                "the NavHost must contain the '$route' destination",
            )
        }

        assertEquals(
            Destinations.ONBOARDING,
            navController.currentBackStackEntry?.destination?.route,
            "onboarding is the M0 start destination",
        )
        assertEquals("viewer/abc", Destinations.viewer("abc"))
    }
}
