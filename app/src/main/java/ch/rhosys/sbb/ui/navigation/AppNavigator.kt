package ch.rhosys.sbb.ui.navigation

import androidx.navigation.NavController
import ch.rhosys.sbb.ui.search.SearchNavigationBridge

/**
 * Tabs are independent nested graphs: leaving a tab always saves its stack, and showing a
 * tab always restores it. The only other decision is [resetTab] — discard that saved stack
 * first, so the tab shows its root screen.
 */
class AppNavigator(
    private val navController: NavController,
    private val searchNavigationBridge: SearchNavigationBridge,
) {
    fun selectTab(tab: Tab) {
        navController.navigate(tab.route) {
            // The Home screen, not the Home tab's graph: popping to the graph would pop the
            // Home screen too.
            popUpTo(Screen.Home.route) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    /** "Plan this trip" (every Home trigger) — the Search tab's root, with the new from/to. */
    fun startTripSearch(from: String, to: String) {
        searchNavigationBridge.request(from, to)
        resetTab(Tab.Search)
    }

    /** A journey was just started — the Journeys tab's root, which shows it. */
    fun showStartedJourney() = resetTab(Tab.Journeys)

    fun finishOnboarding() {
        navController.navigate(Tab.Home.route) {
            popUpTo(Screen.Onboarding.route) { inclusive = true }
        }
    }

    private fun resetTab(tab: Tab) {
        // Open tab: pop to its root. Otherwise: drop its saved stack.
        if (!navController.popBackStack(tab.root.route, inclusive = false)) {
            navController.clearBackStack(tab.route)
        }
        selectTab(tab)
    }
}
