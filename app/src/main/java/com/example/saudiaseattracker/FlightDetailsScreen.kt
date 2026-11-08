package com.example.saudiaseattracker

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlightDetailsScreen(navController: NavController, viewModel: FlightViewModel) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("إعدادات الرحلة والبحث", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

        // نوع الرحلة (ثابت حالياً كذهاب فقط كما طلبت)
        OutlinedTextField(
            value = "ذهاب فقط (One Way)",
            onValueChange = {},
            label = { Text("نوع الرحلة") },
            readOnly = true,
            modifier = Modifier.fillMaxWidth(),
            enabled = false
        )
        Spacer(modifier = Modifier.height(16.dp))

        // من
        OutlinedTextField(
            value = viewModel.searchOrigin,
            onValueChange = { viewModel.searchOrigin = it },
            label = { Text("من (كود المطار، مثال: JED)") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))

        // إلى
        OutlinedTextField(
            value = viewModel.searchDestination,
            onValueChange = { viewModel.searchDestination = it },
            label = { Text("إلى (كود المطار، مثال: RUH)") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))

        // التاريخ
        OutlinedTextField(
            value = viewModel.searchDate,
            onValueChange = { viewModel.searchDate = it },
            label = { Text("التاريخ (YYYY-MM-DD)") },
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = { 
                // الانتقال لصفحة البحث للبحث برقم الرحلة
                navController.navigate(Screen.Search.route)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("حفظ والذهاب للبحث")
        }
    }
}
