package ch.rhosys.sbb.ui.navigation

sealed class Screen(val route: String) {
    object Onboarding  : Screen("onboarding")
    object Home        : Screen("home")
    object HomeEdit    : Screen("home_edit")
    // A single persistent destination — always showing whatever was last searched. A fresh
    // from/to (Home tile taps/drags) is pushed in via SearchNavigationBridge rather than a
    // navigation argument.
    object Search      : Screen("search")
    object Journeys    : Screen("journeys")
    object Settings    : Screen("settings")
}

/**
 * A bottom-nav tab. Each tab is its own nested nav graph, so its back stack is saved and
 * restored independently of the others — one tab's screens never sit on another's stack.
 */
enum class Tab(val root: Screen) {
    Home(Screen.Home),
    Search(Screen.Search),
    Journeys(Screen.Journeys),
    Settings(Screen.Settings);

    val route = "tab/${name.lowercase()}"

    // Trip details open from both Search and Journeys, so each of those tabs has its own copy.
    val tripReview = "$route/trip_review"
    val fares = "$route/fares"
}
