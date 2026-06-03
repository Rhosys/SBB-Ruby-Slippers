package ch.rhosys.sbb

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ch.rhosys.sbb.ui.error.StartupErrorScreen
import ch.rhosys.sbb.ui.navigation.AppNavHost
import ch.rhosys.sbb.ui.navigation.Screen
import ch.rhosys.sbb.ui.theme.SbbRubySlippersTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            SbbRubySlippersTheme {
                val app = application as SbbRubySlippersApp
                if (app.startupError != null) {
                    StartupErrorScreen(app.startupError!!)
                    return@SbbRubySlippersTheme
                }

                val navController = rememberNavController()
                val backStack by navController.currentBackStackEntryAsState()
                val currentRoute = backStack?.destination?.route

                val tabs = listOf(
                    Triple(Screen.Search,       "Search",     Icons.Default.Search),
                    Triple(Screen.Stationboard, "Departures", Icons.Default.DateRange),
                    Triple(Screen.Settings,     "Settings",   Icons.Default.Settings),
                )

                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            tabs.forEach { (screen, label, icon) ->
                                NavigationBarItem(
                                    selected = currentRoute == screen.route,
                                    onClick = {
                                        navController.navigate(screen.route) {
                                            popUpTo(Screen.Search.route) { saveState = true }
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
                ) { innerPadding ->
                    AppNavHost(navController, modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}
