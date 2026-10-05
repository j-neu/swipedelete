package com.swipedelete.app.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.swipedelete.core.model.ItemId
import com.swipedelete.feature.deck.ui.DeckViewModel
import com.swipedelete.feature.viewer.ViewerPager
import com.swipedelete.feature.viewer.ViewerScreen

/**
 * The viewer destination (SPEC 5.4, decision 5).
 *
 * It shares the deck entry's [DeckViewModel], so the viewer pages the same frozen
 * deck the user was swiping and reads bytes through the same gateway
 * ([DeckViewModel.openStream], i.e. `StorageGateway.openStream`) — not the
 * throwing placeholder the M1 assembly review found.
 */
@Composable
fun ViewerDestination(navController: NavHostController, itemId: String) {
    val deckEntry = remember(navController) {
        runCatching { navController.getBackStackEntry(Destinations.DECK) }.getOrNull()
    }
    val deck: DeckViewModel = if (deckEntry != null) hiltViewModel(deckEntry) else hiltViewModel()
    val state by deck.state.collectAsStateWithLifecycle()
    val startIndex = remember(state.items, itemId) {
        state.items.indexOfFirst { it.id == ItemId(itemId) }.coerceAtLeast(0)
    }

    ViewerScreen(
        itemId = ItemId(itemId),
        pager = ViewerPager(items = state.items, startIndex = startIndex),
        openStream = deck::openStream,
        onDismiss = { navController.popBackStack() },
        onOpenDock = { navController.popBackStack() },
    )
}
