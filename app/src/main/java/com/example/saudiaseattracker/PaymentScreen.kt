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
fun PaymentScreen(
    navController: NavController,
    viewModel: FlightViewModel
) {
    val orderId = viewModel.orderId.collectAsStateWithLifecycle().value
    val cartId = viewModel.cartId.collectAsStateWithLifecycle().value
    val paymentMethodsRaw = viewModel.paymentMethodsRaw.collectAsStateWithLifecycle().value.orEmpty()
    val logs = viewModel.searchLogs.collectAsStateWithLifecycle().value
    val context = LocalContext.current

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
                Text("Cart ID:", fontWeight = FontWeight.Bold)
                Text(cartId ?: "-", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("طرق الدفع (JSON):", style = MaterialTheme.typography.titleSmall)
            Button(
                onClick = {
                    val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cb.setPrimaryClip(ClipData.newPlainText("PaymentJSON", paymentMethodsRaw))
                    Toast.makeText(context, "تم النسخ!", Toast.LENGTH_SHORT).show()
                },
                enabled = paymentMethodsRaw.isNotEmpty()
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
                    text = paymentMethodsRaw.ifEmpty { "بانتظار الاستجابة..." },
                    modifier = Modifier.padding(12.dp),
                    fontFamily = FontFamily.Monospace, fontSize = 10.sp
                )
            }
        }

        HorizontalDivider()
        Text("📋 السجل:", style = MaterialTheme.typography.titleSmall)
        logs.takeLast(30).forEach { line ->
            Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
    }
}
