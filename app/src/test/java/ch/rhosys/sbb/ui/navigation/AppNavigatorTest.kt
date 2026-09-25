package ch.rhosys.sbb.ui.navigation

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import ch.rhosys.sbb.ui.search.SearchNavigationBridge
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Part 2 of the Home → Search reset work: what the Search tab ends up showing for each
// navigation call. No Home screen here — the calls are made directly, and every
// destination is a placeholder, so only the back-stack behaviour is under test.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = android.app.Application::class)
class AppNavigatorTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var navController: NavHostController
    private lateinit var navigator: AppNavigator
    private val bridge = SearchNavigationBridge()

    @Before
    fun setUp() {
        composeRule.setContent {
            navController = rememberNavController()
            NavHost(navController, startDestination = Screen.Home.route) {
                listOf(
                    Screen.Home, Screen.Search, Screen.TripReview,
                    Screen.FaresTeaser, Screen.Journeys, Screen.Settings,
                ).forEach { screen -> composable(screen.route) { Text(screen.route) } }
            }
        }
        composeRule.runOnIdle { navigator = AppNavigator(navController, bridge) }
    }

    private fun act(block: () -> Unit) {
        composeRule.runOnIdle(block)
        composeRule.waitForIdle()
    }

    // In-screen navigation exactly as AppNavHost does it (e.g. Search → trip details).
    private fun open(screen: Screen) = act { navController.navigate(screen.route) }

    private fun back() = act { navController.popBackStack() }

    private fun currentRoute(): String? = composeRule.runOnIdle { navController.currentDestination?.route }

    private fun previousRoute(): String? = composeRule.runOnIdle { navController.previousBackStackEntry?.destination?.route }

    // --- startTripSearch: "plan this trip" ---

    @Test
    fun `startTripSearch with trip details open on the Search tab shows Search with details gone`() {
        act { navigator.selectTab(Screen.Search) }
        open(Screen.TripReview)
        act { navigator.selectTab(Screen.Home) }

        act { navigator.startTripSearch("A", "B") }

        assertEquals(Screen.Search.route, currentRoute())
        assertEquals(Screen.Home.route, previousRoute())
    }

    @Test
    fun `startTripSearch with details then fares open shows Search with both gone`() {
        act { navigator.selectTab(Screen.Search) }
        open(Screen.TripReview)
        open(Screen.FaresTeaser)
        act { navigator.selectTab(Screen.Home) }

        act { navigator.startTripSearch("A", "B") }

        assertEquals(Screen.Search.route, currentRoute())
        assertEquals(Screen.Home.route, previousRoute())
    }

    @Test
    fun `startTripSearch with no saved Search tab shows Search`() {
        act { navigator.startTripSearch("A", "B") }
        assertEquals(Screen.Search.route, currentRoute())
    }

    @Test
    fun `startTripSearch hands from and to to the search screen`() {
        act { navigator.startTripSearch("Winterthur", "Bern") }
        val request = bridge.pending.value
        assertEquals("Winterthur" to "Bern", request?.from to request?.to)
    }

    @Test
    fun `startTripSearch then Back returns to Home`() {
        act { navigator.startTripSearch("A", "B") }
        back()
        assertEquals(Screen.Home.route, currentRoute())
    }

    @Test
    fun `startTripSearch then open details then Back returns to Search`() {
        act { navigator.startTripSearch("A", "B") }
        open(Screen.TripReview)
        back()
        assertEquals(Screen.Search.route, currentRoute())
    }

    // --- selectTab(Search): "just clicking around" ---

    @Test
    fun `selectTab Search with trip details open on that tab shows the same details again`() {
        act { navigator.selectTab(Screen.Search) }
        open(Screen.TripReview)
        act { navigator.selectTab(Screen.Home) }

        act { navigator.selectTab(Screen.Search) }

        assertEquals(Screen.TripReview.route, currentRoute())
    }

    @Test
    fun `selectTab Search with results open shows results and sends no new request`() {
        act { navigator.selectTab(Screen.Search) }
        act { navigator.selectTab(Screen.Home) }

        act { navigator.selectTab(Screen.Search) }

        assertEquals(Screen.Search.route, currentRoute())
        assertEquals(null, bridge.pending.value)
    }

    @Test
    fun `selectTab Search after going through Settings still restores the details`() {
        act { navigator.selectTab(Screen.Search) }
        open(Screen.TripReview)
        act { navigator.selectTab(Screen.Settings) }

        act { navigator.selectTab(Screen.Search) }

        assertEquals(Screen.TripReview.route, currentRoute())
    }

    // --- isolation between tabs ---

    @Test
    fun `startTripSearch leaves the Journeys tab's saved details alone`() {
        act { navigator.selectTab(Screen.Journeys) }
        open(Screen.TripReview)
        act { navigator.selectTab(Screen.Home) }

        act { navigator.startTripSearch("A", "B") }
        act { navigator.selectTab(Screen.Journeys) }

        assertEquals(Screen.TripReview.route, currentRoute())
    }
}
