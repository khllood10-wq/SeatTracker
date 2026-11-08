package com.example.saudiaseattracker

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController

@Composable
fun PaymentScreen(
    navController: NavController,
    viewModel: FlightViewModel
) {
    val orderId = viewModel.orderId.collectAsStateWithLifecycle().value
    val pnr = viewModel.pnr.collectAsStateWithLifecycle().value
    val sadadReady = viewModel.sadadReady.collectAsStateWithLifecycle().value
    val logs = viewModel.searchLogs.collectAsStateWithLifecycle().value

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("💳 صفحة الدفع", style = MaterialTheme.typography.headlineMedium)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Order ID:", fontWeight = FontWeight.Bold)
                Text(orderId ?: "غير متوفر", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                if (pnr != null) {
                    Text("✅ PNR (رقم الحجز):", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text(pnr, fontFamily = FontFamily.Monospace, fontSize = 16.sp)
                }
            }
        }

        if (!sadadReady) {
            Button(
                onClick = { viewModel.payWithSadad() },
                modifier = Modifier.fillMaxWidth(),
                enabled = orderId != null
            ) {
                Text("💳 اختيار سداد وتأكيد الدفع")
            }
            Text(
                "ملاحظة: بعد التأكيد، اذهب للبنك وسدد الفاتورة",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("🎉 تم تأكيد الحجز!", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("رقم الحجز: $pnr", fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(8.dp))
                    Text("اذهب للبنك وسدد الفاتورة خلال 24 ساعة", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        HorizontalDivider()
        Text("📋 السجل:", style = MaterialTheme.typography.titleSmall)
        logs.takeLast(30).forEach { line ->
            Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
    }
}
