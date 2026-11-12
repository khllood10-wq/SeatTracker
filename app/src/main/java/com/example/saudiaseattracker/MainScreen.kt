package com.example.saudiaseattracker

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(navController: NavController, viewModel: FlightViewModel) {
    val steps by viewModel.steps.collectAsStateWithLifecycle()
    val isRunning by viewModel.isRunning.collectAsStateWithLifecycle()
    val logs by viewModel.searchLogs.collectAsStateWithLifecycle()
    val pnr by viewModel.finalPnr.collectAsStateWithLifecycle()
    val sadad by viewModel.finalSadad.collectAsStateWithLifecycle()
    val sessionReady by viewModel.sessionReady.collectAsStateWithLifecycle()
    val sessionStatus by viewModel.sessionStatus.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current

    var refreshTokenInput by remember { mutableStateOf(viewModel.msalRefreshToken) }
    val paymentStatus = steps.find { it.id == "payment" }?.status ?: StepStatus.PENDING

    var remainingMs by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            remainingMs = viewModel.sessionExpiryMs() - System.currentTimeMillis()
            delay(1000)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("✈️ حجز تلقائي — Hyper", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        // بطاقة حالة الجلسة
        Card(
            colors = CardDefaults.cardColors(
                containerColor = when {
                    sessionReady && remainingMs > 60_000 -> Color(0xFFE8F5E9)
                    sessionReady -> Color(0xFFFFF3E0)
                    else -> MaterialTheme.colorScheme.errorContainer
                }
            )
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = when {
                        sessionReady && remainingMs > 60_000 -> Icons.Default.CheckCircle
                        sessionReady -> Icons.Default.Warning
                        else -> Icons.Default.Error
                    },
                    contentDescription = null,
                    tint = when {
                        sessionReady && remainingMs > 60_000 -> Color(0xFF00C853)
                        sessionReady -> Color(0xFFFFA726)
                        else -> Color(0xFFD32F2F)
                    }
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(sessionStatus, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    if (sessionReady && remainingMs > 0) {
                        val mins = remainingMs / 60_000
                        val secs = ((remainingMs % 60_000) / 1000).toInt()
                        Text(
                            "⏱️ reese84 صالح لـ $mins:${secs.toString().padStart(2, '0')}",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // 👇 خانة Refresh Token للتجديد التلقائي 👇
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(12.dp)) {
                Text("🔄 Refresh Token (للتجديد التلقائي):", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                OutlinedTextField(
                    value = refreshTokenInput,
                    onValueChange = { 
                        refreshTokenInput = it
                        if(it.length > 50) {
                            viewModel.saveRefreshToken(it)
                        }
                    },
                    placeholder = { Text("0.A... أو 1.A...", fontSize = 10.sp) },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    maxLines = 2
                )
                if (viewModel.msalRefreshToken.isNotBlank()) {
                    Text("✅ التجديد التلقائي مفعّل", color = Color(0xFF00C853), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }

        // زر البدء
        Button(
            onClick = { viewModel.runFullBooking() },
            enabled = !isRunning,
            modifier = Modifier.fillMaxWidth().height(64.dp)
        ) {
            if (isRunning) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            else Text("🚀 بدء الحجز", fontWeight = FontWeight.Bold)
        }

        // باقي الواجهة (وضع المراقبة، السجل، إلخ...)
        if (logs.isNotEmpty()) {
            Text("📝 السجل:", fontWeight = FontWeight.Bold)
            Card(border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                SelectionContainer {
                    Column(Modifier.padding(10.dp)) {
                        for (log in logs.take(20)) {
                            Text(log, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
}
