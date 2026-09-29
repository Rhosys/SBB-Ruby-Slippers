package ch.rhosys.sbb.ui.navigation

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
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
                        if (tab == Tab.Home) placeholder(Screen.HomeEdit.route)
                    }
                }
            }
        }
        composeRule.runOnIdle { navigator = AppNavigator(navController) }
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

    @Test
    fun `selectTab Home after Journeys shows Home`() {
        act { navigator.selectTab(Tab.Journeys) }
        act { navigator.selectTab(Tab.Home) }
        assertEquals(Screen.Home.route, currentRoute())
    }

    @Test
    fun `selectTab Home after details on another tab shows Home`() {
        act { navigator.selectTab(Tab.Search) }
        open(Tab.Search.tripReview)
        act { navigator.selectTab(Tab.Journeys) }

        act { navigator.selectTab(Tab.Home) }
        assertEquals(Screen.Home.route, currentRoute())
    }

    @Test
    fun `selectTab Home restores Home's own screens`() {
        open(Screen.HomeEdit.route)
        act { navigator.selectTab(Tab.Search) }

        act { navigator.selectTab(Tab.Home) }
        assertEquals(Screen.HomeEdit.route, currentRoute())
    }

    @Test
    fun `selectTab restores each tab's details independently`() {
        act { navigator.selectTab(Tab.Search) }
        open(Tab.Search.tripReview)
        act { navigator.selectTab(Tab.Journeys) }
        open(Tab.Journeys.tripReview)
        open(Tab.Journeys.fares)

        act { navigator.selectTab(Tab.Search) }
        assertEquals(Tab.Search.tripReview, currentRoute())
        act { navigator.selectTab(Tab.Journeys) }
        assertEquals(Tab.Journeys.fares, currentRoute())
    }

    @Test
    fun `selectTab Search after going through Settings still restores the details`() {
        act { navigator.selectTab(Tab.Search) }
        open(Tab.Search.tripReview)
        act { navigator.selectTab(Tab.Settings) }

        act { navigator.selectTab(Tab.Search) }
        assertEquals(Tab.Search.tripReview, currentRoute())
    }

    @Test
    fun `selectTab on the current tab keeps its details`() {
        act { navigator.selectTab(Tab.Search) }
        open(Tab.Search.tripReview)

        act { navigator.selectTab(Tab.Search) }
        assertEquals(Tab.Search.tripReview, currentRoute())
    }

    @Test
    fun `Back from a tab's root returns to Home`() {
        act { navigator.selectTab(Tab.Search) }
        back()
        assertEquals(Screen.Home.route, currentRoute())
    }

    @Test
    fun `Back from details returns to that tab's root`() {
        act { navigator.selectTab(Tab.Journeys) }
        open(Tab.Journeys.tripReview)
        back()
        assertEquals(Screen.Journeys.route, currentRoute())
    }
}
