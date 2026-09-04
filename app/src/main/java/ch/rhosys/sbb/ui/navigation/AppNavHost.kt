package ch.rhosys.sbb.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import ch.rhosys.sbb.ui.fares.FaresTeaserScreen
import ch.rhosys.sbb.ui.home.HomeScreen
import ch.rhosys.sbb.ui.places.HomeEditScreen
import ch.rhosys.sbb.ui.journey.JourneysScreen
import ch.rhosys.sbb.ui.journey.TripReviewScreen
import ch.rhosys.sbb.ui.onboarding.OnboardingScreen
import ch.rhosys.sbb.ui.search.ConnectionSearchScreen
import ch.rhosys.sbb.ui.search.SearchNavigationBridge
import ch.rhosys.sbb.ui.settings.SettingsScreen

@Composable
fun AppNavHost(
    navController: NavHostController,
    startDestination: String,
    searchNavigationBridge: SearchNavigationBridge,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
    ) {
        composable(Screen.Onboarding.route) {
            OnboardingScreen(
                onComplete = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.Home.route) {
            HomeScreen(
                onNavigateToSearch = { from, to ->
                    searchNavigationBridge.request(from, to)
                    navController.navigate(Screen.Search.route) {
                        popUpTo(Screen.Home.route) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                onNavigateToJourneys = {
                    navController.navigate(Screen.Journeys.route)
                },
                onNavigateToHomeEdit = {
                    navController.navigate(Screen.HomeEdit.route)
                },
            )
        }

        composable(Screen.HomeEdit.route) {
            HomeEditScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable(Screen.Search.route) {
            ConnectionSearchScreen(
                onNavigateToReview = {
                    navController.navigate(Screen.TripReview.route)
                },
                onNavigateToFares = {
                    navController.navigate(Screen.FaresTeaser.route)
                },
            )
        }

        composable(Screen.TripReview.route) {
            TripReviewScreen(
                onNavigateBack = { navController.popBackStack() },
                onJourneyStarted = {
                    navController.navigate(Screen.Journeys.route) {
                        popUpTo(Screen.Home.route) { saveState = false }
                    }
                },
                onNavigateToFares = {
                    navController.navigate(Screen.FaresTeaser.route)
                },
            )
        }

        composable(Screen.FaresTeaser.route) {
            FaresTeaserScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable(Screen.Journeys.route) {
            JourneysScreen()
        }

        composable(Screen.Settings.route) { SettingsScreen() }
    }
}
