package com.swipedelete.app.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.swipedelete.app.R
import com.swipedelete.app.permissions.WholeLibraryAffordance
import com.swipedelete.app.permissions.rememberCapabilityRequester
import com.swipedelete.app.permissions.rememberTieredFeatureGate
import com.swipedelete.feature.dashboard.overview.DashboardRoute
import com.swipedelete.feature.dashboard.history.HistoryRoute
import com.swipedelete.feature.dashboard.quarantine.QuarantineRoute
import com.swipedelete.feature.deck.ui.DeckRoute
import com.swipedelete.feature.settings.grants.GrantsRoute
import com.swipedelete.feature.settings.onboarding.OnboardingRoute

/**
 * The M0 app shell. Every route in [Destinations] is declared here, in the order
 * M0-T15 froze; `onboarding` and `settings` render the real screens (M0-T16),
 * `deck` and `viewer` now render the assembled M1 loop (Task M1-T15), and the
 * remaining M1 routes render [PlaceholderScreen] until they ship.
 *
 * `deckScreen` and `viewerScreen` stay overridable composition seams so the graph
 * is testable without a Hilt-enabled activity; production uses the defaults,
 * which render the real `@HiltViewModel`-backed destinations.
 */
@Composable
fun AppNavHost(
    navController: NavHostController = rememberNavController(),
    startDestination: String = Destinations.START,
    onboardingScreen: @Composable (onFinished: () -> Unit) -> Unit = { onFinished ->
        OnboardingRoute(onFinished = onFinished)
    },
    settingsScreen: @Composable () -> Unit = { GrantsRoute() },
    deckScreen: @Composable () -> Unit = {
        DeckRoute(
            onOpenItem = { itemId -> navController.navigate(Destinations.viewer(itemId.value)) },
            topContent = {
                OutlinedButton(onClick = { navController.navigate(Destinations.DASHBOARD) }) {
                    Text(stringResource(R.string.nav_dashboard))
                }
                WholeLibraryAffordance(
                    controller = rememberCapabilityRequester(),
                    gate = rememberTieredFeatureGate(),
                )
            },
        )
    },
    viewerScreen: @Composable (itemId: String) -> Unit = { itemId ->
        ViewerDestination(navController = navController, itemId = itemId)
    },
) {
    NavHost(navController = navController, startDestination = startDestination) {
        composable(Destinations.ONBOARDING) {
            onboardingScreen { navController.navigate(Destinations.DECK) }
        }
        composable(Destinations.DECK) {
            deckScreen()
        }
        composable(
            route = Destinations.VIEWER,
            arguments = listOf(navArgument(Destinations.ITEM_ID_ARG) { type = NavType.StringType }),
        ) { entry ->
            viewerScreen(entry.arguments?.getString(Destinations.ITEM_ID_ARG).orEmpty())
        }
        composable(Destinations.DASHBOARD) {
            DashboardRoute(
                onOpenQuarantine = { navController.navigate(Destinations.QUARANTINE) },
                onOpenHistory = { navController.navigate(Destinations.HISTORY) },
                onAddFolder = { navController.navigate(Destinations.SETTINGS) },
            )
        }
        composable(Destinations.QUARANTINE) {
            QuarantineRoute()
        }
        composable(Destinations.HISTORY) {
            HistoryRoute()
        }
        composable(Destinations.SETTINGS) {
            settingsScreen()
        }
    }
}

@Composable
private fun PlaceholderScreen(title: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = title)
    }
}
