package com.example.saudiaseattracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
    
    NavHost(navController = navController, startDestination = Screen.Search.route) {
        composable(Screen.Search.route) {
            // هنا بنستدعي صفحة البحث لاحقاً
            Text("هذي صفحة البحث عن الرحلات")
        }
        composable(Screen.Passengers.route) {
            // هنا بنستدعي صفحة المسافرين لاحقاً
            Text("هذي صفحة المسافرين")
        }
        composable(Screen.Flights.route) {
            // هنا بنستدعي صفحة الرحلات لاحقاً
            Text("هذي صفحة الرحلات والنتائج")
        }
    }
}
