package com.example.saudiaseattracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
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

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    // ✨ نسخة واحدة مشتركة بين كل الشاشات
    val viewModel: FlightViewModel = viewModel()

    Scaffold { padding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Login.route,
            modifier = Modifier.padding(padding)
        ) {
            composable(Screen.Login.route) {
                LoginScreen(navController = navController, viewModel = viewModel)
            }
            composable(Screen.Search.route) {
                SearchScreen(navController = navController, viewModel = viewModel)
            }
            composable(Screen.FlightDetails.route) {
                FlightDetailsScreen(navController = navController, viewModel = viewModel)
            }
            composable(Screen.Passengers.route) {
                PassengersScreen(navController = navController, viewModel = viewModel)
            }
            composable(Screen.Seats.route) {
                SeatsScreen(navController = navController, viewModel = viewModel)
            }
            composable(Screen.Payment.route) {
                PaymentScreen(navController = navController, viewModel = viewModel)
            }
        }
    }
}
