package com.example.saudiaseattracker

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    navController: androidx.navigation.NavController,
    viewModel: FlightViewModel = viewModel()
) {
    var flightNumber by remember { mutableStateOf("") }
    val isSearching by viewModel.isSearching.collectAsStateWithLifecycle()
    val searchLogs by viewModel.searchLogs.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("البحث عن رحلة", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = flightNumber,
            onValueChange = { flightNumber = it },
            label = { Text("رقم الرحلة أو الوجهة") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isSearching
        )
        
        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                viewModel.startSearchingFlight(flightNumber) {
                    // عند توفر الرحلة، ننتقل لصفحة بيانات الرحلة
                    navController.navigate(Screen.FlightDetails.route)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isSearching && flightNumber.isNotBlank()
        ) {
            Text(if (isSearching) "جاري البحث المستمر..." else "بدء البحث")
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text("سجل البحث:", style = MaterialTheme.typography.titleMedium)
        
        // السجل الكبير
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(searchLogs) { log ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(text = log, modifier = Modifier.padding(12.dp))
                }
            }
        }
    }
}
