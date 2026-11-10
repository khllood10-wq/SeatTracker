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
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppNavigation()
                }
            }
        }
    }
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val viewModel: FlightViewModel = viewModel()
    Scaffold { padding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Login.route,
            modifier = Modifier.padding(padding)
        ) {
            composable(Screen.Login.route) { LoginScreen(navController, viewModel) }
            composable(Screen.Search.route) { SearchScreen(navController, viewModel) }
            composable(Screen.Passengers.route) { PassengersScreen(navController, viewModel) }
            composable(Screen.AutoConfirm.route) { AutoConfirmScreen(navController, viewModel) }
            composable(Screen.Replay.route) { ReplayScreen(navController, viewModel) }
            composable(Screen.UnencryptedPassenger.route) { UnencryptedPassengerScreen(navController, viewModel) }
            composable(Screen.Order.route) { OrderScreen(navController, viewModel) }
            composable(Screen.Payment.route) { PaymentScreen(navController, viewModel) }
            composable(Screen.KeyCapture.route) { KeyCaptureScreen(navController) }
        }
    }
}
