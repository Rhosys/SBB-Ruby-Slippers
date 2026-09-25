package ch.rhosys.sbb.ui.navigation

import androidx.navigation.NavController
import ch.rhosys.sbb.ui.search.SearchNavigationBridge

/**
 * The two ways of getting to a tab, kept apart because they mean different things:
 *
 * - [selectTab] is "just clicking around" (bottom nav) — each tab comes back exactly as the
 *   user left it, e.g. still showing a trip's details.
 * - [startTripSearch] is "plan this trip" (every Home trigger) — always a brand-new search.
 */
class AppNavigator(
    private val navController: NavController,
    private val searchNavigationBridge: SearchNavigationBridge,
) {
    fun selectTab(screen: Screen) {
        navController.navigate(screen.route) {
            popUpTo(Screen.Home.route) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun startTripSearch(from: String, to: String) {
        searchNavigationBridge.request(from, to)
        // Throw away the Search tab's saved stack (e.g. a trip's details opened earlier) so
        // the new search is what shows — then open Search without restoring anything.
        navController.clearBackStack(Screen.Search.route)
        navController.navigate(Screen.Search.route) {
            popUpTo(Screen.Home.route) { saveState = true }
            launchSingleTop = true
        }
    }
}
