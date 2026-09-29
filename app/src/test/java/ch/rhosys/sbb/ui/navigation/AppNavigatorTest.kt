package ch.rhosys.sbb.ui.navigation

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import ch.rhosys.sbb.ui.search.SearchNavigationBridge
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// What each tab ends up showing for each navigation call. The graph has AppNavHost's shape
// (one nested graph per tab) with placeholder screens, so only back-stack behaviour is tested.
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
            NavHost(navController, startDestination = Tab.Home.route) {
                Tab.entries.forEach { tab ->
                    navigation(startDestination = tab.root.route, route = tab.route) {
                        placeholder(tab.root.route)
                        placeholder(tab.tripReview)
                        placeholder(tab.fares)
                    }
                }
            }
        }
        composeRule.runOnIdle { navigator = AppNavigator(navController, bridge) }
    }

    private fun NavGraphBuilder.placeholder(route: String) = composable(route) { Text(route) }

    private fun act(block: () -> Unit) {
        composeRule.runOnIdle(block)
        composeRule.waitForIdle()
    }

    // In-tab navigation exactly as AppNavHost does it (e.g. Search → trip details).
    private fun open(route: String) = act { navController.navigate(route) }

    private fun back() = act { navController.popBackStack() }

    private fun currentRoute(): String? = composeRule.runOnIdle { navController.currentDestination?.route }

    // --- tabs are independent ---

    @Test
    fun `selectTab Home after Journeys shows Home`() {
        act { navigator.selectTab(Tab.Journeys) }
        act { navigator.selectTab(Tab.Home) }
        assertEquals(Screen.Home.route, currentRoute())
    }

    @Test
    fun `selectTab Home after showStartedJourney shows Home`() {
        act { navigator.selectTab(Tab.Search) }
        open(Tab.Search.tripReview)
        act { navigator.showStartedJourney() }
        assertEquals(Screen.Journeys.route, currentRoute())

        act { navigator.selectTab(Tab.Home) }
        assertEquals(Screen.Home.route, currentRoute())
    }

    @Test
    fun `selectTab Search after showStartedJourney shows the Search tab as left`() {
        act { navigator.selectTab(Tab.Search) }
        open(Tab.Search.tripReview)
        act { navigator.showStartedJourney() }

        act { navigator.selectTab(Tab.Search) }
        assertEquals(Tab.Search.tripReview, currentRoute())
    }

    @Test
    fun `showStartedJourney from the Journeys tab's own details shows Journeys`() {
        act { navigator.selectTab(Tab.Journeys) }
        open(Tab.Journeys.tripReview)
        act { navigator.showStartedJourney() }
        assertEquals(Screen.Journeys.route, currentRoute())
    }

    @Test
    fun `startTripSearch leaves the Journeys tab's saved details alone`() {
        act { navigator.selectTab(Tab.Journeys) }
        open(Tab.Journeys.tripReview)
        act { navigator.selectTab(Tab.Home) }

        act { navigator.startTripSearch("A", "B") }
        act { navigator.selectTab(Tab.Journeys) }

        assertEquals(Tab.Journeys.tripReview, currentRoute())
    }

    // --- startTripSearch: "plan this trip" ---

    @Test
    fun `startTripSearch with trip details open on the Search tab shows Search`() {
        act { navigator.selectTab(Tab.Search) }
        open(Tab.Search.tripReview)
        act { navigator.selectTab(Tab.Home) }

        act { navigator.startTripSearch("A", "B") }

        assertEquals(Screen.Search.route, currentRoute())
    }

    @Test
    fun `startTripSearch with details then fares open shows Search`() {
        act { navigator.selectTab(Tab.Search) }
        open(Tab.Search.tripReview)
        open(Tab.Search.fares)
        act { navigator.selectTab(Tab.Home) }

        act { navigator.startTripSearch("A", "B") }

        assertEquals(Screen.Search.route, currentRoute())
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
        open(Tab.Search.tripReview)
        back()
        assertEquals(Screen.Search.route, currentRoute())
    }

    // --- selectTab(Search): "just clicking around" ---

    @Test
    fun `selectTab Search with trip details open on that tab shows the same details again`() {
        act { navigator.selectTab(Tab.Search) }
        open(Tab.Search.tripReview)
        act { navigator.selectTab(Tab.Home) }

        act { navigator.selectTab(Tab.Search) }

        assertEquals(Tab.Search.tripReview, currentRoute())
    }

    @Test
    fun `selectTab Search with results open shows results and sends no new request`() {
        act { navigator.selectTab(Tab.Search) }
        act { navigator.selectTab(Tab.Home) }

        act { navigator.selectTab(Tab.Search) }

        assertEquals(Screen.Search.route, currentRoute())
        assertEquals(null, bridge.pending.value)
    }

    @Test
    fun `selectTab Search after going through Settings still restores the details`() {
        act { navigator.selectTab(Tab.Search) }
        open(Tab.Search.tripReview)
        act { navigator.selectTab(Tab.Settings) }

        act { navigator.selectTab(Tab.Search) }

        assertEquals(Tab.Search.tripReview, currentRoute())
    }
}
