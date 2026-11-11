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
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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

    val paymentStep = steps.find { it.id == "payment" }
    val paymentStatus = paymentStep?.status ?: StepStatus.PENDING

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("✈️ حجز تلقائي", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        // ⭐⭐ وضع المراقبة Toggle ⭐⭐
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (viewModel.monitoringMode)
                    MaterialTheme.colorScheme.tertiaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            ),
            border = if (viewModel.monitoringMode)
                BorderStroke(2.dp, MaterialTheme.colorScheme.tertiary)
            else null
        ) {
            Row(
                Modifier.padding(12.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (viewModel.monitoringMode)
                        Icons.Default.Visibility else Icons.Default.VisibilityOff,
                    contentDescription = null,
                    tint = if (viewModel.monitoringMode)
                        MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "👁️ وضع المراقبة",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Text(
                        if (viewModel.monitoringMode)
                            "✅ مفعّل: يصل لمرحلة الدفع (PNR + SADAD) بدون إتمام"
                        else
                            "معطّل: حجز كامل (طلب + دفع + إيميل)",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = viewModel.monitoringMode,
                    onCheckedChange = {
                        viewModel.monitoringMode = it
                        if (!viewModel.isRunning.value) viewModel.clearResults()
                    },
                    enabled = !isRunning
                )
            }
        }

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
            colors = ButtonDefaults.buttonColors(
                containerColor = if (viewModel.monitoringMode)
                    MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.primary
            )
        ) {
            if (isRunning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(Modifier.width(12.dp))
                Text("جاري التنفيذ...", fontWeight = FontWeight.Bold)
            } else {
                Text(
                    if (viewModel.monitoringMode)
                        "👁️ بدء المراقبة"
                    else
                        "🚀 بدء الحجز التلقائي",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        }

        // ── Stepper ──
        Text("مراحل الحجز:", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        steps.forEach { step -> StepRow(step) }

        // ── Result Card ──
        if (pnr.isNotEmpty()) {
            when (paymentStatus) {
                StepStatus.SUCCESS -> {
                    // Full booking success
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        border = BorderStroke(3.dp, Color(0xFF00C853))
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text("🎉 تم الحجز الكامل!", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00C853))
                            Spacer(Modifier.height(8.dp))
                            PnrSadadInfo(pnr, sadad, clipboard)
                            Spacer(Modifier.height(10.dp))
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                                Column(Modifier.padding(10.dp)) {
                                    Text("⚠️ اذهب للبنك الآن", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text("ادفع رقم SADAD. بعد الدفع يصلك الإيميل + التذكرة تلقائياً.", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
                StepStatus.SKIPPED -> {
                    // Monitoring mode
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                        border = BorderStroke(3.dp, MaterialTheme.colorScheme.tertiary)
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text("👁️ وضع المراقبة — وصلنا لمرحلة الدفع", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary)
                            Spacer(Modifier.height(8.dp))
                            PnrSadadInfo(pnr, sadad, clipboard)
                            Spacer(Modifier.height(10.dp))
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                                Column(Modifier.padding(10.dp)) {
                                    Text("⚠️ لم يتم إرسال الدفع", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    Text("• الطلب موجود (PNR) لكنه غير مدفوع", fontSize = 11.sp)
                                    Text("• سيلغى تلقائياً بعد ~5 ساعات", fontSize = 11.sp)
                                    Text("• لإتمامه: أوقف المراقبة وأعد التشغيل", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
                StepStatus.FAILED -> {
                    // Payment failed
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        border = BorderStroke(3.dp, MaterialTheme.colorScheme.error)
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text("⚠️ فشل الدفع", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(8.dp))
                            PnrSadadInfo(pnr, sadad, clipboard)
                            Spacer(Modifier.height(8.dp))
                            Text("قد يكون لديك حجز سابق معلق. ادفعه أو انتظر انتهاء المهلة.", fontSize = 11.sp)
                        }
                    }
                }
                else -> {
                    // PNR exists but payment pending
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text("🆔 PNR: $pnr", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            if (sadad.isNotEmpty()) Text("💳 SADAD: $sadad", fontWeight = FontWeight.Bold, fontSize = 14.sp)
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
private fun PnrSadadInfo(pnr: String, sadad: String, clipboard: androidx.compose.ui.platform.ClipboardManager) {
    Row {
        Text("PNR: ", fontWeight = FontWeight.Bold)
        Text(pnr, fontFamily = FontFamily.Monospace, fontSize = 18.sp, color = Color(0xFF00C853))
    }
    if (sadad.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        Row {
            Text("SADAD: ", fontWeight = FontWeight.Bold)
            Text(sadad, fontFamily = FontFamily.Monospace, fontSize = 18.sp, color = Color(0xFF00C853))
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { clipboard.setText(AnnotatedString(sadad)) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("📋 نسخ SADAD") }
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
                StepStatus.SKIPPED -> MaterialTheme.colorScheme.tertiaryContainer
                StepStatus.PENDING -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            Modifier.padding(10.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when (step.status) {
                    StepStatus.SUCCESS -> Icons.Default.CheckCircle
                    StepStatus.FAILED -> Icons.Default.Error
                    StepStatus.SKIPPED -> Icons.Default.RemoveCircleOutline
                    StepStatus.RUNNING, StepStatus.PENDING -> Icons.Default.RadioButtonUnchecked
                },
                contentDescription = null,
                tint = when (step.status) {
                    StepStatus.SUCCESS -> Color(0xFF00C853)
                    StepStatus.FAILED -> Color(0xFFD32F2F)
                    StepStatus.SKIPPED -> MaterialTheme.colorScheme.tertiary
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
