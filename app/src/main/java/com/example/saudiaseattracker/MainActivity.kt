package com.example.saudiaseattracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.saudiaseattracker.ui.theme.SaudiaSeatTrackerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SaudiaSeatTrackerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation()
                }
            }
        }
    }
}

data class BottomTab(val screen: Screen, val icon: ImageVector)

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val viewModel: FlightViewModel = viewModel()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDest = backStackEntry?.destination

    val tabs = listOf(
        BottomTab(Screen.Home, Icons.Default.Home),
        BottomTab(Screen.Passenger, Icons.Default.Person),
        BottomTab(Screen.Flight, Icons.Default.Flight)
    )

    val showBottomBar = tabs.any { tab ->
        currentDest?.hierarchy?.any { it.route == tab.screen.route } == true
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        val selected = currentDest?.hierarchy?.any {
                            it.route == tab.screen.route
                        } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(tab.screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.screen.title) },
                            label = { Text(tab.screen.title) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(padding)
        ) {
            composable(Screen.Home.route) {
                MainScreen(navController, viewModel)
            }
            composable(Screen.Passenger.route) {
                PassengerDataScreen(navController, viewModel)
            }
            composable(Screen.Flight.route) {
                FlightDataScreen(navController, viewModel)
            }
            composable(Screen.TokenChallenge.route) {
                TokenChallengeScreen(navController, viewModel)
            }
            composable(Screen.TokenInitTester.route) {
                TokenInitTesterScreen(navController, viewModel)
            }
        }
    }
}
