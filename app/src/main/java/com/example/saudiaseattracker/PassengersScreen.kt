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

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("بيانات المسافرين", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("البالغين", style = MaterialTheme.typography.bodyLarge)
            Row {
                Button(onClick = { if (adults > 1) adults-- }) { Text("-") }
                Text(text = " $adults ", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge)
                Button(onClick = { adults++ }) { Text("+") }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = { navController.navigate(Screen.Payment.route) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("المتابعة إلى الدفع (سداد)")
        }
    }
}
