package ch.rhosys.sbb

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.first
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ch.rhosys.sbb.data.local.preferences.UserPreferencesRepository
import ch.rhosys.sbb.ui.error.StartupErrorScreen
import ch.rhosys.sbb.ui.journey.JourneyStateHolder
import ch.rhosys.sbb.ui.journey.MissedBoardingDialog
import ch.rhosys.sbb.ui.navigation.AppNavHost
import ch.rhosys.sbb.ui.navigation.Screen
import ch.rhosys.sbb.ui.theme.SbbRubySlippersTheme
import dagger.hilt.android.AndroidEntryPoint
import java.time.Instant
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var prefs: UserPreferencesRepository
    @Inject lateinit var journeyStateHolder: JourneyStateHolder

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val isFreshStart = savedInstanceState == null

        setContent {
            SbbRubySlippersTheme {
                val app = application as SbbRubySlippersApp
                if (app.startupError != null) {
                    StartupErrorScreen(app.startupError!!)
                    return@SbbRubySlippersTheme
                }

                val hasOnboarded by prefs.hasCompletedOnboarding
                    .collectAsStateWithLifecycle(initialValue = null)

                if (hasOnboarded == null) return@SbbRubySlippersTheme

                val startDestination = if (hasOnboarded == true)
                    Screen.Home.route
                else
                    Screen.Onboarding.route

                val navController = rememberNavController()
                val backStack by navController.currentBackStackEntryAsState()
                val currentRoute = backStack?.destination?.route

                if (isFreshStart && hasOnboarded == true) {
                    LaunchedEffect(Unit) {
                        val journey = prefs.activeJourney.first() ?: return@LaunchedEffect
                        if (journey.arrivalEpoch > Instant.now().epochSecond) {
                            navController.navigate(Screen.Journeys.route)
                        }
                    }
                }

                val missedBoardingPrompt by journeyStateHolder.missedBoardingPrompt
                    .collectAsStateWithLifecycle(initialValue = false)
                val activeJourney by journeyStateHolder.activeJourney
                    .collectAsStateWithLifecycle(initialValue = null)

                if (missedBoardingPrompt && activeJourney != null) {
                    MissedBoardingDialog(
                        fromName = activeJourney!!.from.displayName(),
                        onMissedIt = { journeyStateHolder.clear() },
                        onDifferentRoute = { journeyStateHolder.clear() },
                        onStillOnIt = { journeyStateHolder.dismissMissedBoardingPrompt() },
                    )
                }

                val tabScreens = listOf(
                    Triple(Screen.Home,     "Home",     Icons.Default.Home),
                    Triple(Screen.Journeys, "Journeys", Icons.Default.DateRange),
                    Triple(Screen.Settings, "Settings", Icons.Default.Settings),
                )

                val hideBottomNav = currentRoute in setOf(
                    Screen.Onboarding.route,
                )

                Scaffold(
                    bottomBar = {
                        if (!hideBottomNav) {
                            NavigationBar {
                                tabScreens.forEach { (screen, label, icon) ->
                                    NavigationBarItem(
                                        selected = currentRoute == screen.route,
                                        onClick = {
                                            navController.navigate(screen.route) {
                                                popUpTo(Screen.Home.route) { saveState = true }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        },
                                        icon = { Icon(icon, contentDescription = null) },
                                        label = { Text(label) },
                                    )
                                }
                            }
                        }
                    }
                ) { innerPadding ->
                    AppNavHost(
                        navController = navController,
                        startDestination = startDestination,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }
}
