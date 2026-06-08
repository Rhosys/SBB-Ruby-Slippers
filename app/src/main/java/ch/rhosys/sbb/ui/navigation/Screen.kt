package ch.rhosys.sbb.ui.navigation

sealed class Screen(val route: String) {
    object Onboarding  : Screen("onboarding")
    object Home        : Screen("home")
    object Search      : Screen("search?from={from}&to={to}") {
        fun withArgs(from: String = "", to: String = "") =
            "search?from=${encode(from)}&to=${encode(to)}"
        private fun encode(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
    }
    object TripReview    : Screen("trip_review")
    object FaresTeaser   : Screen("fares_teaser")
    object HomeEdit      : Screen("home_edit")
    object Journeys      : Screen("journeys")
    object Settings    : Screen("settings")
}
