package com.example.saudiaseattracker

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(navController: NavController) {
    var origin by remember { mutableStateOf("") }
    var destination by remember { mutableStateOf("") }
    var date by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(text = "البحث عن رحلة", style = MaterialTheme.typography.headlineMedium)
        
        OutlinedTextField(
            value = origin,
            onValueChange = { origin = it },
            label = { Text("من (مثال: AHB)") },
            modifier = Modifier.fillMaxWidth()
        )
        
        OutlinedTextField(
            value = destination,
            onValueChange = { destination = it },
            label = { Text("إلى (مثال: JED)") },
            modifier = Modifier.fillMaxWidth()
        )
        
        OutlinedTextField(
            value = date,
            onValueChange = { date = it },
            label = { Text("التاريخ (YYYY-MM-DD)") },
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = { navController.navigate(Screen.Passengers.route) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("التالي: المسافرين")
        }
    }
}
