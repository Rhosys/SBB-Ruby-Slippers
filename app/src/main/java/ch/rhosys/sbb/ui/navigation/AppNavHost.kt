package ch.rhosys.sbb.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import ch.rhosys.sbb.ui.home.HomeScreen
import ch.rhosys.sbb.ui.journey.JourneyStripScreen
import ch.rhosys.sbb.ui.onboarding.OnboardingScreen
import ch.rhosys.sbb.ui.search.ConnectionSearchScreen
import ch.rhosys.sbb.ui.settings.SettingsScreen
import ch.rhosys.sbb.ui.stationboard.DepartureDetailsScreen
import ch.rhosys.sbb.ui.stationboard.StationboardScreen

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
                onNavigateToJourney = {
                    navController.navigate(Screen.Journey.route)
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
                onNavigateToJourney = {
                    navController.navigate(Screen.Journey.route)
                },
            )
        }

        composable(Screen.Stationboard.route) {
            StationboardScreen(
                onNavigateToDetails = {
                    navController.navigate(Screen.DepartureDetails.route)
                },
            )
        }

        composable(Screen.DepartureDetails.route) {
            DepartureDetailsScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable(Screen.Journey.route) {
            JourneyStripScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable(Screen.Settings.route) { SettingsScreen() }
    }
}
