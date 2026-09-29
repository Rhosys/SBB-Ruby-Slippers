package ch.rhosys.sbb.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import ch.rhosys.sbb.ui.fares.FaresTeaserScreen
import ch.rhosys.sbb.ui.home.HomeScreen
import ch.rhosys.sbb.ui.places.HomeEditScreen
import ch.rhosys.sbb.ui.journey.JourneysScreen
import ch.rhosys.sbb.ui.journey.TripReviewScreen
import ch.rhosys.sbb.ui.onboarding.OnboardingScreen
import ch.rhosys.sbb.ui.search.ConnectionSearchScreen
import ch.rhosys.sbb.ui.settings.SettingsScreen

@Composable
fun AppNavHost(
    navController: NavHostController,
    startDestination: String,
    navigator: AppNavigator,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
    ) {
        composable(Screen.Onboarding.route) {
            OnboardingScreen(onComplete = navigator::finishOnboarding)
        }

        tab(Tab.Home) {
            composable(Screen.Home.route) {
                HomeScreen(
                    onNavigateToSearch = navigator::startTripSearch,
                    onNavigateToJourneys = { navigator.selectTab(Tab.Journeys) },
                    onNavigateToHomeEdit = { navController.navigate(Screen.HomeEdit.route) },
                )
            }
            composable(Screen.HomeEdit.route) {
                HomeEditScreen(onNavigateBack = { navController.popBackStack() })
            }
        }

        tab(Tab.Search) {
            composable(Screen.Search.route) {
                ConnectionSearchScreen(
                    onNavigateToReview = { navController.navigate(Tab.Search.tripReview) },
                    onNavigateToFares = { navController.navigate(Tab.Search.fares) },
                )
            }
            tripDetails(Tab.Search, navController, navigator)
        }

        tab(Tab.Journeys) {
            composable(Screen.Journeys.route) {
                JourneysScreen(
                    onNavigateToTripReview = { navController.navigate(Tab.Journeys.tripReview) },
                )
            }
            tripDetails(Tab.Journeys, navController, navigator)
        }

        tab(Tab.Settings) {
            composable(Screen.Settings.route) { SettingsScreen() }
        }
    }
}

private fun NavGraphBuilder.tab(tab: Tab, builder: NavGraphBuilder.() -> Unit) =
    navigation(startDestination = tab.root.route, route = tab.route, builder = builder)

private fun NavGraphBuilder.tripDetails(
    tab: Tab,
    navController: NavHostController,
    navigator: AppNavigator,
) {
    composable(tab.tripReview) {
        TripReviewScreen(
            onNavigateBack = { navController.popBackStack() },
            onJourneyStarted = navigator::showStartedJourney,
            onNavigateToFares = { navController.navigate(tab.fares) },
        )
    }
    composable(tab.fares) {
        FaresTeaserScreen(onNavigateBack = { navController.popBackStack() })
    }
}
