package com.swipedelete.app.nav

/**
 * Every route the app shell knows in M0. Only `onboarding` and `settings` have a
 * real screen yet — the rest are declared so later tasks (and deep links) can land
 * on a route that exists rather than one that has to be invented.
 */
object Destinations {

    const val ONBOARDING: String = "onboarding"
    const val DECK: String = "deck"
    const val VIEWER: String = "viewer/{itemId}"
    const val DASHBOARD: String = "dashboard"
    const val QUARANTINE: String = "quarantine"
    const val HISTORY: String = "history"
    const val SETTINGS: String = "settings"

    /** The viewer's path argument name. */
    const val ITEM_ID_ARG: String = "itemId"

    /** The M0 start destination. */
    const val START: String = ONBOARDING

    /** Every declared route, in declaration order. */
    val all: List<String> = listOf(ONBOARDING, DECK, VIEWER, DASHBOARD, QUARANTINE, HISTORY, SETTINGS)

    /** The concrete route for one item, e.g. `viewer/abc`. */
    fun viewer(itemId: String): String = "viewer/$itemId"
}
