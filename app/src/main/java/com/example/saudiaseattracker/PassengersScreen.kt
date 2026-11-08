package com.example.saudiaseattracker

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassengersScreen(navController: NavController, viewModel: FlightViewModel) {
    val foundFlight by viewModel.foundFlight.collectAsState()
    val cartId by viewModel.cartId.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("بيانات المسافرين", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

        if (foundFlight != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("ملخص الحجز:", fontWeight = FontWeight.Bold)
                    Text("الرحلة: ${foundFlight!!.flightNumber}")
                    Text("المسار: ${foundFlight!!.origin} ✈️ ${foundFlight!!.destination}")
                    Text("السعر: ${foundFlight!!.price} ريال")
                    Text("رقم السلة: ${cartId ?: "جاري الإنشاء..."}")
                }
            }
        } else {
            Text("لا توجد بيانات رحلة.")
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text("إدخال بيانات المسافر (قريباً)", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = "",
            onValueChange = {},
            label = { Text("الاسم الكامل") },
            modifier = Modifier.fillMaxWidth(),
            enabled = false
        )

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = { navController.navigate(Screen.Payment.route) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("الانتقال للدفع (سداد)")
        }
    }
}
