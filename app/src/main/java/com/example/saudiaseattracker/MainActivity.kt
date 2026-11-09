package com.example.saudiaseattracker

import android.content.Context
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
        val prefs = getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
        val hasKey = !prefs.getString("rsa_public_key", null).isNullOrEmpty()

        setContent {
            SaudiaSeatTrackerTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppNavigation(startAtKeyCapture = !hasKey)
                }
            }
        }
    }
}

@Composable
fun AppNavigation(startAtKeyCapture: Boolean) {
    val navController = rememberNavController()
    val viewModel: FlightViewModel = viewModel()
    Scaffold { padding ->
        NavHost(
            navController = navController,
            startDestination = if (startAtKeyCapture) Screen.KeyCapture.route else Screen.Login.route,
            modifier = Modifier.padding(padding)
        ) {
            composable(Screen.KeyCapture.route) {
                KeyCaptureScreen(navController = navController)
            }
            composable(Screen.Login.route) {
                LoginScreen(navController = navController, viewModel = viewModel)
            }
            composable(Screen.Search.route) {
                SearchScreen(navController = navController, viewModel = viewModel)
            }
            composable(Screen.Passengers.route) {
                PassengersScreen(navController = navController, viewModel = viewModel)
            }
            composable(Screen.Payment.route) {
                PaymentScreen(navController = navController, viewModel = viewModel)
            }
        }
    }
}
