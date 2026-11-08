package com.example.saudiaseattracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
                MainAppScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen() {
    val navController = rememberNavController()
    val flightViewModel: FlightViewModel = viewModel()
    
    val items = listOf(
        Screen.Search,
        Screen.FlightDetails,
        Screen.Passengers
    )

    Scaffold(
        bottomBar = {
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = navBackStackEntry?.destination?.route
            
            if (currentRoute != Screen.Login.route) {
                NavigationBar {
                    val currentDestination = navBackStackEntry?.destination
                    
                    items.forEach { screen ->
                        NavigationBarItem(
                            icon = { 
                                val icon = when(screen) {
                                    Screen.Search -> Icons.Default.Home
                                    Screen.FlightDetails -> Icons.Default.Flight
                                    Screen.Passengers -> Icons.Default.Person
                                    else -> Icons.Default.Home
                                }
                                Icon(icon, contentDescription = screen.title) 
                            },
                            label = { Text(screen.title) },
                            selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController, 
            startDestination = Screen.Login.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Login.route) { 
                LoginScreen(navController, flightViewModel) 
            }
            composable(Screen.Search.route) { 
                SearchScreen(navController, flightViewModel) 
            }
            composable(Screen.FlightDetails.route) { 
                FlightDetailsScreen(navController, flightViewModel) // تمرير الـ ViewModel
            }
            composable(Screen.Passengers.route) { 
                PassengersScreen(navController) 
            }
            composable(Screen.Payment.route) { 
                PaymentScreen(navController) 
            }
        }
    }
}
