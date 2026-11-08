package com.example.saudiaseattracker

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@Composable
fun FlightDetailsScreen(navController: NavController) {
    // جلب التاريخ المستقبلي لعرضه في الواجهة
    val calendar = Calendar.getInstance()
    calendar.add(Calendar.DAY_OF_YEAR, 2)
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val displayDate = sdf.format(calendar.time)

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("تفاصيل الرحلة المحجوزة", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("الخطوط السعودية", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(8.dp))
                Text("المسار: JED ✈️ AHB")
                Text("التاريخ: $displayDate") // التاريخ الديناميكي
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = { navController.navigate(Screen.Passengers.route) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("إدخال بيانات المسافرين")
        }
    }
}
