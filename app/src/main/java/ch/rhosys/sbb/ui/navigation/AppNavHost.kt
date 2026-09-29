package ch.rhosys.sbb.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import ch.rhosys.sbb.ui.search.SearchNavigationBridge
import ch.rhosys.sbb.ui.settings.SettingsScreen

@Composable
fun AppNavHost(
    navController: NavHostController,
    startDestination: String,
    navigator: AppNavigator,
    searchNavigationBridge: SearchNavigationBridge,
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
                    onNavigateToSearch = { from, to ->
                        searchNavigationBridge.request(from, to)
                        navigator.selectTab(Tab.Search)
                    },
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
            tripDetails(Tab.Search, navController, navigator) {
                // A new search handed to this tab replaces whatever it was showing.
                val pending by searchNavigationBridge.pending.collectAsState()
                LaunchedEffect(pending) {
                    if (pending != null) navController.popBackStack(Screen.Search.route, inclusive = false)
                }
            }
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
    whileOpen: @Composable () -> Unit = {},
) {
    composable(tab.tripReview) {
        whileOpen()
        TripReviewScreen(
            onNavigateBack = { navController.popBackStack() },
            onJourneyStarted = {
                // A started trip's details have served their purpose; the Journeys tab
                // decides for itself how to show the journey.
                navController.popBackStack()
                navigator.selectTab(Tab.Journeys)
            },
            onNavigateToFares = { navController.navigate(tab.fares) },
        )
    }
    composable(tab.fares) {
        whileOpen()
        FaresTeaserScreen(onNavigateBack = { navController.popBackStack() })
    }
}
