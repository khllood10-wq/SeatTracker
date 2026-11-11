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

    val paymentStatus = steps.find { it.id == "payment" }?.status ?: StepStatus.PENDING

    // عداد تنازلي لصلاحية reese84
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
                            "⏱️ reese84 صالح لـ $mins:${secs.toString().padStart(2, '0')} — يُجدد تلقائياً",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (sessionReady) {
                        Text(
                            "⏱️ سيُجدد تلقائياً قبل انتهائه",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(
                    onClick = { viewModel.refreshSession() },
                    enabled = !isRunning
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "تجديد يدوي")
                }
            }
        }

        // زر البدء
        Button(
            onClick = { viewModel.runFullBooking() },
            enabled = !isRunning && sessionReady,
            modifier = Modifier.fillMaxWidth().height(64.dp)
        ) {
            if (isRunning) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            else Text(
                if (viewModel.monitoringMode) "👁️ بدء المراقبة" else "🚀 بدء الحجز",
                fontWeight = FontWeight.Bold
            )
        }

        // وضع المراقبة
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("👁️ وضع المراقبة", fontWeight = FontWeight.Bold)
                    Text(
                        if (viewModel.monitoringMode) "✅ يصل للدفع بدون إتمام" else "معطّل",
                        fontSize = 11.sp
                    )
                }
                Switch(viewModel.monitoringMode, { viewModel.monitoringMode = it }, enabled = !isRunning)
            }
        }

        // اختبارات
        OutlinedButton({ navController.navigate(Screen.GetTest.route) }, Modifier.fillMaxWidth()) {
            Text("🧪 اختبار GET", fontSize = 12.sp)
        }
        OutlinedButton({ navController.navigate(Screen.TokenInitTester.route) }, Modifier.fillMaxWidth()) {
            Text("🔬 اختبار token/init", fontSize = 12.sp)
        }

        // بيانات المسافر والرحلة
        OutlinedButton({ navController.navigate(Screen.Passenger.route) }, Modifier.fillMaxWidth()) {
            Text("👤 ${viewModel.passengerData.firstName} ${viewModel.passengerData.lastName}", fontSize = 12.sp)
        }
        OutlinedButton({ navController.navigate(Screen.Flight.route) }, Modifier.fillMaxWidth()) {
            Text("✈️ ${viewModel.flightData.from} → ${viewModel.flightData.to}", fontSize = 12.sp)
        }

        // المراحل
        if (steps.any { it.status != StepStatus.PENDING }) {
            Text("المراحل:", fontWeight = FontWeight.Bold)
            steps.forEach { s -> StepRow(s) }
        }

        // PNR / SADAD
        if (pnr.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (paymentStatus == StepStatus.SUCCESS)
                        Color(0xFFE8F5E9) else MaterialTheme.colorScheme.surfaceVariant
                ),
                border = BorderStroke(3.dp, Color(0xFF00C853))
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("🆔 PNR: $pnr", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    if (sadad.isNotEmpty()) {
                        Text("💳 SADAD: $sadad", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Button(
                            { clipboard.setText(AnnotatedString(sadad)) },
                            Modifier.fillMaxWidth()
                        ) { Text("📋 نسخ SADAD") }
                    }
                }
            }
        }

        // السجل
        if (logs.isNotEmpty()) {
            Text("📝 السجل:", fontWeight = FontWeight.Bold)
            Card(border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                SelectionContainer {
                    Column(Modifier.padding(10.dp)) {
                        logs.take(20).forEach {
                            Text(it, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepRow(step: BookingStep) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (step.status) {
                StepStatus.RUNNING -> MaterialTheme.colorScheme.secondaryContainer
                StepStatus.SUCCESS -> MaterialTheme.colorScheme.primaryContainer
                StepStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
                StepStatus.SKIPPED -> MaterialTheme.colorScheme.tertiaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when (step.status) {
                    StepStatus.SUCCESS -> Icons.Default.CheckCircle
                    StepStatus.FAILED -> Icons.Default.Error
                    StepStatus.SKIPPED -> Icons.Default.RemoveCircleOutline
                    else -> Icons.Default.RadioButtonUnchecked
                },
                null,
                tint = when (step.status) {
                    StepStatus.SUCCESS -> Color(0xFF00C853)
                    StepStatus.FAILED -> Color(0xFFD32F2F)
                    else -> MaterialTheme.colorScheme.primary
                }
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(step.title, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                if (step.detail.isNotEmpty()) Text(step.detail, fontSize = 10.sp)
            }
            if (step.status == StepStatus.RUNNING)
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        }
    }
}
