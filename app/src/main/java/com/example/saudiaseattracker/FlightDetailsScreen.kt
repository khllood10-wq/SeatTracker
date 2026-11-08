package com.example.saudiaseattracker

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

@Composable
fun FlightDetailsScreen(navController: NavController) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("تفاصيل الرحلة", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("الخطوط السعودية", style = MaterialTheme.typography.titleLarge)
                Text("الرحلة: SV1234")
                Text("أبها (AHB) -> جدة (JED)")
                Text("التاريخ: 2026-11-07")
                Text("الحالة: متاحة للحجز", color = MaterialTheme.colorScheme.primary)
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = { navController.navigate(Screen.Passengers.route) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("التالي: بيانات المسافرين")
        }
    }
}
