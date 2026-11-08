package com.example.saudiaseattracker

import androidx.compose.foundation.BorderStroke
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
fun PassengersScreen(
    navController: NavController,
    viewModel: FlightViewModel = viewModel()
) {
    val cartId         by viewModel.cartId.collectAsState()
    val seatsReady     by viewModel.seatsReady.collectAsState()
    val rawCartResponse by viewModel.rawCartResponse.collectAsState()
    val searchLogs     by viewModel.searchLogs.collectAsState()

    // ── (1) عند الدخول: أنشئ السلة تلقائياً ─────────────
    LaunchedEffect(Unit) {
        viewModel.createCartAndProceedToPassenger()
    }

    // ── (2) عند توفر cartId: أرسل بيانات المسافر تلقائياً ──
    LaunchedEffect(cartId) {
        if (cartId != null && !seatsReady) {
            viewModel.submitPassengersAndGoToSeats()
        }
    }

    // ── (3) عند اكتمال كل شيء: انتقل لشاشة المقاعد ────────
    LaunchedEffect(seatsReady) {
        if (seatsReady) {
            navController.navigate(Screen.Seats.route) {
                popUpTo(Screen.Passengers.route) { inclusive = true }
            }
        }
    }

    // ── واجهة العرض (للمراقبة فقط — لا أزرار) ─────────────
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "⏳ جاري تجهيز الحجز تلقائياً...",
            style = MaterialTheme.typography.titleLarge
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(
                text = when {
                    seatsReady             -> "✅ جاهز — الانتقال للمقاعد"
                    cartId != null         -> "📤 إرسال بيانات المسافر..."
                    else                   -> "🛒 إنشاء السلة..."
                },
                style = MaterialTheme.typography.bodyMedium
            )
        }

        if (cartId != null) {
            Text("Cart ID: $cartId", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }

        HorizontalDivider()

        Text("📋 السجل:", style = MaterialTheme.typography.titleSmall)
        searchLogs.takeLast(30).forEach { line ->
            Text(
                text = line,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 14.sp
            )
        }

        if (rawCartResponse.isNotEmpty()) {
            HorizontalDivider()
            Text("📦 استجابة السلة (Raw):", style = MaterialTheme.typography.titleSmall)
            Card(border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                Text(
                    text = rawCartResponse,
                    modifier = Modifier.padding(8.dp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    lineHeight = 13.sp
                )
            }
        }
    }
}
