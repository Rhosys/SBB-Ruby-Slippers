package ch.rhosys.sbb.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import ch.rhosys.sbb.ui.fares.FaresTeaserScreen
import ch.rhosys.sbb.ui.home.HomeScreen
import ch.rhosys.sbb.ui.places.PlacesScreen
import ch.rhosys.sbb.ui.journey.JourneysScreen
import ch.rhosys.sbb.ui.journey.TripReviewScreen
import ch.rhosys.sbb.ui.onboarding.OnboardingScreen
import ch.rhosys.sbb.ui.search.ConnectionSearchScreen
import ch.rhosys.sbb.ui.settings.SettingsScreen

@Composable
fun AppNavHost(
    navController: NavHostController,
    startDestination: String,
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
                    navController.navigate(Screen.Search.withArgs(from, to))
                },
                onNavigateToJourneys = {
                    navController.navigate(Screen.Journeys.route)
                },
                onNavigateToPlaces = {
                    navController.navigate(Screen.Places.route)
                },
            )
        }

        composable(Screen.Places.route) {
            PlacesScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToSearch = { from, to ->
                    navController.navigate(Screen.Search.withArgs(from, to))
                },
            )
        }

        composable(
            route = Screen.Search.route,
            arguments = listOf(
                navArgument("from") { type = NavType.StringType; defaultValue = "" },
                navArgument("to")   { type = NavType.StringType; defaultValue = "" },
            ),
        ) {
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
