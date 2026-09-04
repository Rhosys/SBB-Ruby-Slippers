package ch.rhosys.sbb.ui.navigation

sealed class Screen(val route: String) {
    object Onboarding  : Screen("onboarding")
    object Home        : Screen("home")
    // A single persistent destination — always showing whatever was last searched — so it
    // can live as a bottom-nav tab. A fresh from/to (Home tile taps/drags) is pushed in via
    // SearchNavigationBridge rather than a navigation argument, which would otherwise force
    // a new backstack entry and defeat the "always the latest search" behavior.
    object Search      : Screen("search")
    object TripReview    : Screen("trip_review")
    object FaresTeaser   : Screen("fares_teaser")
    object HomeEdit      : Screen("home_edit")
    object Journeys      : Screen("journeys")
    object Settings      : Screen("settings")
}
