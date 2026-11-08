package com.example.saudiaseattracker

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

@Composable
fun SeatsScreen(navController: NavController, viewModel: FlightViewModel) {
    val cartId by viewModel.cartId.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("🪑 صفحة المقاعد", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Text("✅ وصلنا بنجاح من صفحة المسافرين!", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        cartId?.let {
            Text("Cart ID: $it", style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(16.dp))
        Text("هنا سنضيف اختيار المقعد بعد التقاط HAR التالي.")
    }
}
