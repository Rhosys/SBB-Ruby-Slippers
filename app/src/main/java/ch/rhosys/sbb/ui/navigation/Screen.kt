package ch.rhosys.sbb.ui.navigation

sealed class Screen(val route: String) {
    object Search       : Screen("search")
    object Stationboard : Screen("stationboard")
    object Settings     : Screen("settings")
}
