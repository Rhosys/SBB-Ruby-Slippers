package ch.rhosys.sbb.ui.navigation

import androidx.navigation.NavController

/**
 * Tabs are independent nested graphs: leaving a tab always saves its stack, and showing a
 * tab always restores it. Callers never reset another tab — they hand it data (e.g.
 * SearchNavigationBridge) and the tab itself decides what to show.
 */
class AppNavigator(private val navController: NavController) {
    fun selectTab(tab: Tab) {
        navController.navigate(tab.route) {
            // The Home screen, not the Home tab's graph: popping to the graph would pop the
            // Home screen too.
            popUpTo(Screen.Home.route) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun finishOnboarding() {
        navController.navigate(Tab.Home.route) {
            popUpTo(Screen.Onboarding.route) { inclusive = true }
        }
    }
}
