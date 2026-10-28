package ch.rhosys.sbb.wear

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController

private const val HOME_ROUTE = "home"
private const val CONNECTIONS_ROUTE = "connections"

// launchedPlaceId: a place picked on the "Go to" tile, opened straight on its connections.
@Composable
fun WearApp(
    launchedPlaceId: Long?,
    onLaunchedPlaceHandled: () -> Unit,
    viewModel: WearDataViewModel = viewModel(),
) {
    val journeyData by viewModel.journeyData.collectAsState()
    val places by viewModel.places.collectAsState()
    val navController = rememberSwipeDismissableNavController()

    LaunchedEffect(launchedPlaceId) {
        launchedPlaceId ?: return@LaunchedEffect
        navController.navigate("$CONNECTIONS_ROUTE/$launchedPlaceId") { launchSingleTop = true }
        onLaunchedPlaceHandled()
    }

    Scaffold(
        timeText = { TimeText() },
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
    ) {
        SwipeDismissableNavHost(navController = navController, startDestination = HOME_ROUTE) {
            composable(HOME_ROUTE) {
                JourneyScreen(
                    data = journeyData,
                    places = places,
                    onPlaceClick = { navController.navigate("$CONNECTIONS_ROUTE/${it.id}") },
                )
            }
            composable("$CONNECTIONS_ROUTE/{placeId}") { entry ->
                val placeId = entry.arguments?.getString("placeId")?.toLongOrNull() ?: return@composable
                val connectionsViewModel: ConnectionsViewModel = viewModel()
                LaunchedEffect(placeId) { connectionsViewModel.load(placeId) }
                val state by connectionsViewModel.uiState.collectAsState()
                ConnectionsScreen(
                    state = state,
                    onSave = connectionsViewModel::saveJourney,
                    onRetry = connectionsViewModel::refresh,
                    // Back home, where the saved journey now shows as the active one.
                    onSaved = { navController.popBackStack(HOME_ROUTE, inclusive = false) },
                )
            }
        }
    }
}
