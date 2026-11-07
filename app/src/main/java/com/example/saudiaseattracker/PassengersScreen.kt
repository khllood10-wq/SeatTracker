package com.example.saudiaseattracker

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

@Composable
fun PassengersScreen(navController: NavController) {
    var adults by remember { mutableStateOf(1) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(text = "بيانات المسافرين", style = MaterialTheme.typography.headlineMedium)
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("البالغين")
            Row {
                Button(onClick = { if (adults > 1) adults-- }) { Text("-") }
                Text(text = " $adults ", modifier = Modifier.padding(16.dp))
                Button(onClick = { adults++ }) { Text("+") }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = { navController.navigate(Screen.Flights.route) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("بحث عن الرحلات")
        }
        
        OutlinedButton(
            onClick = { navController.popBackStack() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("رجوع")
        }
    }
}
