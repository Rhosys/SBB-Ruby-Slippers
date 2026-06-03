package ch.rhosys.sbb.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import ch.rhosys.sbb.ui.search.ConnectionSearchScreen
import ch.rhosys.sbb.ui.settings.SettingsScreen
import ch.rhosys.sbb.ui.stationboard.StationboardScreen

@Composable
fun AppNavHost(navController: NavHostController, modifier: Modifier = Modifier) {
    NavHost(
        navController = navController,
        startDestination = Screen.Search.route,
        modifier = modifier,
    ) {
        composable(Screen.Search.route)       { ConnectionSearchScreen() }
        composable(Screen.Stationboard.route) { StationboardScreen() }
        composable(Screen.Settings.route)     { SettingsScreen() }
    }
}
