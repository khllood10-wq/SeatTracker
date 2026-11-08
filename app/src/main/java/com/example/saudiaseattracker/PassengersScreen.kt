package com.example.saudiaseattracker

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassengersScreen(
    navController: NavController,
    viewModel: FlightViewModel
) {
    val cartId = viewModel.cartId.collectAsStateWithLifecycle().value
    val rawCartResponse = viewModel.rawCartResponse.collectAsStateWithLifecycle().value.orEmpty()
    val foundFlight = viewModel.foundFlight.collectAsStateWithLifecycle().value
    val paymentReady = viewModel.paymentReady.collectAsStateWithLifecycle().value
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.createCartAndProceedToPassenger()
    }

    LaunchedEffect(paymentReady) {
        if (paymentReady) {
            navController.navigate(Screen.Payment.route) {
                popUpTo(Screen.Passengers.route) { inclusive = true }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("صفحة المسافرين", style = MaterialTheme.typography.headlineMedium)

        foundFlight?.let { f ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("ملخص الحجز:", fontWeight = FontWeight.Bold)
                    Text("الرحلة: ${f.flightNumber}")
                    Text("المسار: ${f.origin} → ${f.destination}")
                    Text("السعر: ${f.price} SAR")
                    Text("الفئة: ${f.fareFamily}")
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (cartId.isNullOrEmpty()) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("جاري التنفيذ التلقائي...")
            } else {
                Text("✅ cartId: $cartId", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }

        if (paymentReady) {
            Text("💳 جاهز للانتقال للدفع...", color = MaterialTheme.colorScheme.primary)
        }

        HorizontalDivider()

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("استجابة السلة:", style = MaterialTheme.typography.titleSmall)
            Button(
                onClick = {
                    val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cb.setPrimaryClip(ClipData.newPlainText("CartJSON", rawCartResponse))
                    Toast.makeText(context, "تم النسخ!", Toast.LENGTH_SHORT).show()
                },
                enabled = rawCartResponse.isNotEmpty()
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null)
                Text(" نسخ")
            }
        }

        SelectionContainer {
            Card(
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Text(
                    text = rawCartResponse.ifEmpty { "بانتظار الاستجابة..." },
                    modifier = Modifier.padding(12.dp),
                    fontFamily = FontFamily.Monospace, fontSize = 10.sp
                )
            }
        }
    }
}
