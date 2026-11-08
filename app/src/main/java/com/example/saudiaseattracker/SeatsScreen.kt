package com.example.saudiaseattracker

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController

@Composable
fun SeatsScreen(
    navController: NavController,
    viewModel: FlightViewModel = viewModel()
) {
    val searchLogs by viewModel.searchLogs.collectAsState()
    val cartId     by viewModel.cartId.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "🪑 صفحة المقاعد",
            style = MaterialTheme.typography.headlineMedium
        )

        Text(
            text = "تم قبول بيانات المسافرين بنجاح.\n" +
                   "هذه الشاشة مؤقتة — ستستقبل طلبات المقاعد بعد التقاط HAR التالي.",
            style = MaterialTheme.typography.bodyMedium
        )

        if (cartId != null) {
            Card {
                Text(
                    text = "Cart ID: $cartId",
                    modifier = Modifier.padding(12.dp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            }
        }

        HorizontalDivider()

        Text("📋 آخر السجل:", style = MaterialTheme.typography.titleSmall)
        searchLogs.takeLast(15).forEach { line ->
            Text(
                text = line,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 14.sp
            )
        }
    }
}
