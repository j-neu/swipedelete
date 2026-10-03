package com.swipedelete.app.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import com.swipedelete.core.model.ItemId
import com.swipedelete.feature.deck.ui.DeckScreen
import com.swipedelete.feature.settings.grants.GrantsRoute
import com.swipedelete.feature.settings.onboarding.OnboardingRoute
import com.swipedelete.feature.viewer.ViewerPager
import com.swipedelete.feature.viewer.ViewerScreen

/**
 * The M0 app shell. Every route in [Destinations] is declared here, in the order
 * M0-T15 froze; `onboarding` and `settings` render the real screens (M0-T16),
 * and the remaining M1 routes render [PlaceholderScreen] until they ship.
 *
 * [onboardingScreen] and [settingsScreen] are the composition seam that keeps the
 * graph testable: the real screens own a `@HiltViewModel`, which needs a
 * Hilt-enabled activity, while a route test only needs the graph. Production
 * (and the defaults here) render the real screens; a test may substitute inert
 * destinations. The factory of each `NavBackStackEntry` is a plain
 * `SavedStateViewModelFactory`, so a `@HiltViewModel` is resolved through
 * `hiltViewModel()` inside the route, not from the graph.
 *
 * [deckScreen] and [viewerScreen] are the M1 entry points: the deck route hosts
 * the deck surface (card + accessible action bar), and the viewer route reads its
 * `{itemId}` argument and opens the viewer on that item (decision 5).
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
        DeckScreen(item = null, onDecision = {}, onMove = {})
    },
    viewerScreen: @Composable (itemId: String) -> Unit = { itemId ->
        ViewerScreen(
            itemId = ItemId(itemId),
            pager = ViewerPager(items = emptyList()),
            openStream = { error("the viewer pager source lands with the deck view model") },
            onDismiss = {},
            onOpenDock = {},
        )
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
            PlaceholderScreen(title = stringResource(R.string.nav_dashboard))
        }
        composable(Destinations.QUARANTINE) {
            PlaceholderScreen(title = stringResource(R.string.nav_quarantine))
        }
        composable(Destinations.HISTORY) {
            PlaceholderScreen(title = stringResource(R.string.nav_history))
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
