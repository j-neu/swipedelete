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

/**
 * The M0 app shell. Every route in [Destinations] is declared here; non-M0 routes
 * render [PlaceholderScreen] until M1 (deck/viewer/dashboard/quarantine/history)
 * and M0-T16 (onboarding) supply real screens.
 */
@Composable
fun AppNavHost(
    navController: NavHostController = rememberNavController(),
    startDestination: String = Destinations.START,
) {
    NavHost(navController = navController, startDestination = startDestination) {
        composable(Destinations.ONBOARDING) {
            PlaceholderScreen(title = stringResource(R.string.nav_onboarding))
        }
        composable(Destinations.DECK) {
            PlaceholderScreen(title = stringResource(R.string.nav_deck))
        }
        composable(
            route = Destinations.VIEWER,
            arguments = listOf(navArgument(Destinations.ITEM_ID_ARG) { type = NavType.StringType }),
        ) {
            PlaceholderScreen(title = stringResource(R.string.nav_viewer))
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
            PlaceholderScreen(title = stringResource(R.string.nav_settings))
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
