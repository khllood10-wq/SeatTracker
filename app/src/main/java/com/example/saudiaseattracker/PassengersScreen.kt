package com.example.saudiaseattracker

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke // 🔥 تم إضافة هذا السطر
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassengersScreen(navController: NavController, viewModel: FlightViewModel) {
    val foundFlight by viewModel.foundFlight.collectAsState()
    val cartId by viewModel.cartId.collectAsState()
    val rawResponse by viewModel.rawCartResponse.collectAsState()
    val context = LocalContext.current

    // تشغيل إنشاء السلة أول ما الصفحة تفتح
    LaunchedEffect(Unit) {
        if (cartId == null && foundFlight != null && rawResponse == null) {
            viewModel.createCartAndProceedToPassenger()
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("بيانات المسافرين", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

        if (foundFlight != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("ملخص الحجز:", fontWeight = FontWeight.Bold)
                    Text("الرحلة: SV${foundFlight!!.flightNumber}")
                    Text("المسار: ${foundFlight!!.origin} ✈️ ${foundFlight!!.destination}")
                    Text("السعر: ${foundFlight!!.price} ريال")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("رقم السلة: ${cartId ?: "جاري الإنشاء..."}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 🔥 عرض الرد الكامل من السيرفر
        if (rawResponse != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("📄 الرد الكامل من السيرفر (انسخه وأرسله):", style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Raw Response", rawResponse))
                    Toast.makeText(context, "تم نسخ الرد!", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "نسخ", tint = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            
            SelectionContainer {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Text(
                        text = rawResponse ?: "",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("جاري جلب الرد من السيرفر...")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = { navController.navigate(Screen.Payment.route) },
            modifier = Modifier.fillMaxWidth(),
            enabled = cartId != null
        ) {
            Text("الانتقال للدفع (سداد)")
        }
    }
}
