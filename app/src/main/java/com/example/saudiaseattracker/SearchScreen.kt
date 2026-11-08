package com.example.saudiaseattracker

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    navController: NavController,
    viewModel: FlightViewModel
) {
    var flightNumber by remember { mutableStateOf("") }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val searchLogs by viewModel.searchLogs.collectAsStateWithLifecycle()

    // مراقبة الحالة للتنقل التلقائي
    LaunchedEffect(uiState) {
        if (uiState == SearchState.FOUND) {
            // إذا وجدنا الرحلة، ننتقل للمسافرين
            navController.navigate(Screen.Passengers.route)
            viewModel.stopSearch() 
        } else if (uiState == SearchState.TOKEN_EXPIRED) {
            // إذا انتهى التوكن، ننتقل لصفحة التجهيز لجلب توكن جديد
            navController.navigate(Screen.Login.route) 
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("البحث عن رحلة", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = flightNumber,
            onValueChange = { flightNumber = it },
            label = { Text("رقم الرحلة (مثال: SV123)") },
            modifier = Modifier.fillMaxWidth(),
            enabled = uiState != SearchState.SEARCHING
        )
        
        Spacer(modifier = Modifier.height(16.dp))

        if (uiState != SearchState.SEARCHING) {
            Button(
                onClick = { viewModel.startSearchingFlight(flightNumber) },
                modifier = Modifier.fillMaxWidth(),
                enabled = flightNumber.isNotBlank()
            ) {
                Text("بدء البحث التلقائي")
            }
        } else {
            Button(
                onClick = { viewModel.stopSearch() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("إيقاف البحث")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("سجل الاتصال بالسيرفر:", style = MaterialTheme.typography.titleMedium)
            if (uiState == SearchState.SEARCHING) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(searchLogs) { log ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(
                        text = log, 
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}
