package com.example.saudiaseattracker

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlightDataScreen(navController: NavController, viewModel: FlightViewModel) {
    val f = viewModel.flightData
    var from by remember { mutableStateOf(f.from) }
    var to by remember { mutableStateOf(f.to) }
    var date by remember { mutableStateOf(f.date) }
    var flightNumber by remember { mutableStateOf(f.flightNumber) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("✈️ بيانات الرحلة", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        OutlinedTextField(
            value = from,
            onValueChange = { if (it.length <= 3) from = it.uppercase() },
            label = { Text("من (رمز المطار)") },
            placeholder = { Text("JED") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            value = to,
            onValueChange = { if (it.length <= 3) to = it.uppercase() },
            label = { Text("إلى (رمز المطار)") },
            placeholder = { Text("AHB") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            value = date,
            onValueChange = { date = it },
            label = { Text("تاريخ الذهاب (YYYY-MM-DD)") },
            placeholder = { Text("2026-11-17") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            value = flightNumber,
            onValueChange = { flightNumber = it.uppercase() },
            label = { Text("رقم الرحلة") },
            placeholder = { Text("SV8844") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = {
                viewModel.flightData = FlightData(
                    from = from,
                    to = to,
                    date = date,
                    flightNumber = flightNumber
                )
                viewModel.addLog("💾 تم حفظ بيانات الرحلة")
                navController.navigate(Screen.Home.route) {
                    popUpTo(Screen.Home.route) { inclusive = true }
                }
            },
            enabled = from.length == 3 && to.length == 3 &&
                    date.isNotBlank() && flightNumber.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Text("💾 حفظ والعودة", fontWeight = FontWeight.Bold)
        }

        Text(
            "يُستخدم في البحث عن الرحلة وإنشاء السلة.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
