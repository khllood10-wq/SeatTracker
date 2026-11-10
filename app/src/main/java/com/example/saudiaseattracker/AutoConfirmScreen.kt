package com.example.saudiaseattracker

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoConfirmScreen(navController: NavController, viewModel: FlightViewModel) {
    val cartId = viewModel.cartId.collectAsStateWithLifecycle().value
    val response = viewModel.passengerConfirmResponse.collectAsStateWithLifecycle().value.orEmpty()
    val isSuccess = response.contains("HTTP 200") || response.contains("🎉")
    val isSending = response.contains("⏳")

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("✅ تأكيد المسافر (API)", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(12.dp)) {
                Text("cartId: ${cartId ?: "لا يوجد"}", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                Text("البيانات: Khaled Otudi / 1071580342 / 1991-05-10 / SA", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        if (!isSuccess) {
            Button(
                onClick = { viewModel.confirmPassenger() },
                enabled = !isSending && !cartId.isNullOrEmpty(),
                modifier = Modifier.fillMaxWidth().height(60.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                if (isSending) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(10.dp))
                    Text("جاري الإرسال...")
                } else {
                    Text("📤 إرسال تأكيد المسافر", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
        }

        if (response.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(3.dp, if (isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        if (isSuccess) "🎉 نجح! HTTP 200" else "الرد:",
                        fontWeight = FontWeight.Bold, fontSize = 16.sp,
                        color = if (isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(6.dp))
                    SelectionContainer { Text(response.take(1000), fontFamily = FontFamily.Monospace, fontSize = 10.sp) }
                }
            }
        }

        // ⭐ الآن الانتقال لـ OrderScreen وليس PaymentScreen
        if (isSuccess) {
            Button(
                onClick = {
                    navController.navigate(Screen.Order.route) {
                        popUpTo(Screen.Passengers.route) { inclusive = false }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(64.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
            ) {
                Text("➡️ إتمام الحجز + SADAD", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }

        OutlinedButton(
            onClick = { navController.popBackStack() },
            modifier = Modifier.fillMaxWidth()
        ) { Text("← رجوع") }
    }
}
