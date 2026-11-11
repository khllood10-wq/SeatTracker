package com.example.saudiaseattracker

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(navController: NavController, viewModel: FlightViewModel) {
    val steps by viewModel.steps.collectAsStateWithLifecycle()
    val isRunning by viewModel.isRunning.collectAsStateWithLifecycle()
    val logs by viewModel.searchLogs.collectAsStateWithLifecycle()
    val pnr by viewModel.finalPnr.collectAsStateWithLifecycle()
    val sadad by viewModel.finalSadad.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("✈️ حجز تلقائي", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        // ── ملخص بيانات المسافر ──
        SummaryCard(
            title = "👤 المسافر",
            lines = listOf(
                "${viewModel.passengerData.title} ${viewModel.passengerData.firstName} ${viewModel.passengerData.lastName}",
                "الهوية: ${viewModel.passengerData.nationalId}",
                "الميلاد: ${viewModel.passengerData.dateOfBirth}",
                viewModel.passengerData.email
            ),
            onEdit = { navController.navigate(Screen.Passenger.route) }
        )

        // ── ملخص بيانات الرحلة ──
        SummaryCard(
            title = "✈️ الرحلة",
            lines = listOf(
                "${viewModel.flightData.from} ← ${viewModel.flightData.to}",
                "التاريخ: ${viewModel.flightData.date}",
                "الرحلة: ${viewModel.flightData.flightNumber}"
            ),
            onEdit = { navController.navigate(Screen.Flight.route) }
        )

        // ── زر البدء ──
        Button(
            onClick = { viewModel.runFullBooking() },
            enabled = !isRunning && viewModel.passengerData.isValid() && viewModel.flightData.isValid(),
            modifier = Modifier.fillMaxWidth().height(64.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            if (isRunning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(Modifier.width(12.dp))
                Text("جاري الحجز...", fontWeight = FontWeight.Bold)
            } else {
                Text("🚀 بدء الحجز التلقائي", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }

        // ── Stepper ──
        Text("مراحل الحجز:", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        steps.forEach { step ->
            StepRow(step)
        }

        // ── Success card ──
        if (pnr.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                border = BorderStroke(3.dp, Color(0xFF00C853))
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("🎉 تم الحجز!", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00C853))
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Text("PNR: ", fontWeight = FontWeight.Bold)
                        Text(pnr, fontFamily = FontFamily.Monospace, fontSize = 18.sp, color = Color(0xFF00C853))
                    }
                    if (sadad.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("SADAD: ", fontWeight = FontWeight.Bold)
                            Text(sadad, fontFamily = FontFamily.Monospace, fontSize = 18.sp, color = Color(0xFF00C853))
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { clipboard.setText(AnnotatedString(sadad)) },
                                modifier = Modifier.weight(1f)
                            ) { Text("📋 نسخ SADAD") }
                        }
                        Spacer(Modifier.height(8.dp))
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                            Column(Modifier.padding(10.dp)) {
                                Text("⚠️ اذهب للبنك وادفع رقم SADAD", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text("بعد الدفع يصلك الإيميل + التذكرة تلقائياً", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }

        // ── Logs ──
        if (logs.isNotEmpty()) {
            Text("📝 السجل:", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Card(border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                SelectionContainer {
                    Column(Modifier.padding(10.dp)) {
                        logs.take(25).forEach {
                            Text(it, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(title: String, lines: List<String>, onEdit: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = onEdit) { Text("تعديل", fontSize = 12.sp) }
            }
            lines.forEach {
                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StepRow(step: BookingStep) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (step.status) {
                StepStatus.RUNNING -> MaterialTheme.colorScheme.secondaryContainer
                StepStatus.SUCCESS -> MaterialTheme.colorScheme.primaryContainer
                StepStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
                StepStatus.PENDING -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when (step.status) {
                    StepStatus.SUCCESS -> Icons.Default.CheckCircle
                    StepStatus.FAILED -> Icons.Default.Error
                    StepStatus.RUNNING -> Icons.Default.RadioButtonUnchecked
                    StepStatus.PENDING -> Icons.Default.RadioButtonUnchecked
                },
                contentDescription = null,
                tint = when (step.status) {
                    StepStatus.SUCCESS -> Color(0xFF00C853)
                    StepStatus.FAILED -> Color(0xFFD32F2F)
                    StepStatus.RUNNING -> MaterialTheme.colorScheme.primary
                    StepStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(step.title, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                if (step.detail.isNotEmpty()) {
                    Text(step.detail, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (step.status == StepStatus.RUNNING) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        }
    }
}
