package com.example.saudiaseattracker

import android.app.Activity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
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
    val context = LocalContext.current
    val activity = context as? Activity

    val steps by viewModel.steps.collectAsStateWithLifecycle()
    val isRunning by viewModel.isRunning.collectAsStateWithLifecycle()
    val logs by viewModel.searchLogs.collectAsStateWithLifecycle()
    val pnr by viewModel.finalPnr.collectAsStateWithLifecycle()
    val sadad by viewModel.finalSadad.collectAsStateWithLifecycle()
    val sessionReady by viewModel.sessionReady.collectAsStateWithLifecycle()
    val sessionStatus by viewModel.sessionStatus.collectAsStateWithLifecycle()
    val debugEntries by viewModel.debugEntries.collectAsStateWithLifecycle()

    val autoLoopActive by viewModel.autoLoopActive.collectAsStateWithLifecycle()
    val needsWebViewRefresh by viewModel.needsWebViewRefresh.collectAsStateWithLifecycle()
    val autoCaptureMode by viewModel.autoCaptureMode.collectAsStateWithLifecycle()
    val lastCycle by viewModel.autoLoopLastCycle.collectAsStateWithLifecycle()

    val clipboard = LocalClipboardManager.current
    var expandedDebugId by remember { mutableStateOf<Long?>(null) }
    var showWebView by remember { mutableStateOf(false) }
    var reeseTokenForWebView by remember { mutableStateOf<String?>(null) }

    var jwtMinutesLeft by remember { mutableLongStateOf(0L) }
    var hyperSecondsLeft by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        if (viewModel.hasToken()) viewModel.markSessionReady()
        else viewModel.markSessionPreparing()
    }

    // فتح WebView تلقائياً عند الحاجة
    LaunchedEffect(needsWebViewRefresh) {
        if (needsWebViewRefresh && reeseTokenForWebView == null && !showWebView) {
            val t = viewModel.showWebViewCapture()
            if (t != null) {
                reeseTokenForWebView = t
                showWebView = true
            }
        }
    }

    // عدادات
    LaunchedEffect(Unit) {
        while (true) {
            jwtMinutesLeft = viewModel.jwtRemainingMinutes()
            val hyperExpiry = viewModel.sessionExpiryMs()
            hyperSecondsLeft = ((hyperExpiry - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
            kotlinx.coroutines.delay(1000)
        }
    }

    // ═══ WebView (يدوي أو تلقائي) ═══
    if (showWebView && reeseTokenForWebView != null) {
        // Force-close بعد 20 ثانية كحد أقصى
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(20_000)
            if (showWebView) {
                showWebView = false
                reeseTokenForWebView = null
                viewModel.dismissWebView()
            }
        }
        WebViewTokenCapture(
            reese84Token = reeseTokenForWebView!!,
            autoMode = autoCaptureMode,
            onSuccess = { jwt, sid ->
                viewModel.onWebViewJwtCaptured(jwt, sid)
                // لا نغلق WebView إلا إذا وصل sid
                if (sid.isNotBlank()) {
                    showWebView = false
                    reeseTokenForWebView = null
                }
            },
            onCancel = {
                showWebView = false
                reeseTokenForWebView = null
                viewModel.dismissWebView()
            }
        )
        return
    }

    val paymentStatus = steps.find { it.id == "payment" }?.status ?: StepStatus.PENDING

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("✈️ حجز تلقائي", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        // ═══ شريط الحالة ═══
        Card(
            colors = CardDefaults.cardColors(
                containerColor = when {
                    autoLoopActive -> Color(0xFF1B5E20)
                    viewModel.isJwtFresh(0) -> Color(0xFF1B5E20).copy(alpha = 0.4f)
                    sessionReady -> MaterialTheme.colorScheme.secondaryContainer
                    else -> MaterialTheme.colorScheme.errorContainer
                }
            )
        ) {
            Column(Modifier.padding(10.dp)) {
                Text(sessionStatus, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "JWT: ${if (jwtMinutesLeft > 0) "${jwtMinutesLeft}د" else "❌"}",
                        fontSize = 11.sp
                    )
                    Text(
                        "reese84: ${hyperSecondsLeft}s",
                        fontSize = 11.sp
                    )
                    if (autoLoopActive) {
                        Text("🔁 $lastCycle", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // ═══ 1) تجديد Hyper ═══
        Button(
            onClick = { viewModel.refreshSession() },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFA726))
        ) {
            Text("🔄 تجديد reese84 (Hyper)", fontWeight = FontWeight.Bold)
        }

        // ═══ 2) التقاط JWT يدوياً ═══
        Button(
            onClick = {
                val token = viewModel.showWebViewCapture()
                if (token != null) {
                    reeseTokenForWebView = token
                    showWebView = true
                } else {
                    viewModel.addLog("❌ لا reese84 — جدد أولاً")
                }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3))
        ) {
            Text("🌐 التقاط JWT (WebView)", fontWeight = FontWeight.Bold)
        }

        // ═══ 3) بدء/إيقاف الحلقة التلقائية ═══
        Button(
            onClick = {
                if (autoLoopActive) viewModel.stopAutoLoop()
                else {
                    if (!viewModel.isJwtFresh(0)) {
                        viewModel.addLog("⚠️ لا JWT — افتح WebView أولاً")
                    } else {
                        viewModel.startAutoLoop()
                    }
                }
            },
            enabled = viewModel.isJwtFresh(0) || autoLoopActive,
            modifier = Modifier.fillMaxWidth().height(64.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (autoLoopActive) Color(0xFFD32F2F) else Color(0xFF9C27B0)
            )
        ) {
            Text(
                if (autoLoopActive) "⏹️ إيقاف الحلقة التلقائية"
                else "🔁 بدء الحلقة التلقائية",
                fontWeight = FontWeight.Bold, fontSize = 15.sp
            )
        }

        // ═══ 4) حجز واحد (اختبار) ═══
        OutlinedButton(
            onClick = { activity?.let { viewModel.runOnceBooking(it) } },
            enabled = !isRunning && viewModel.isJwtFresh(0),
            modifier = Modifier.fillMaxWidth().height(52.dp),
            border = BorderStroke(2.dp, Color(0xFF00C853)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00C853))
        ) {
            if (isRunning) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text("🚀 حجز واحد (اختبار)", fontWeight = FontWeight.Bold)
        }

        // ═══ وضع المراقبة ═══
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("👁️ وضع المراقبة", fontWeight = FontWeight.Bold)
                    Text(if (viewModel.monitoringMode) "✅ يصل للدفع بدون إتمام" else "معطّل", fontSize = 11.sp)
                }
                Switch(viewModel.monitoringMode, { viewModel.monitoringMode = it }, enabled = !isRunning)
            }
        }

        // ═══ بيانات ═══
        OutlinedButton({ navController.navigate(Screen.Passenger.route) }, Modifier.fillMaxWidth()) {
            Text("👤 ${viewModel.passengerData.firstName} ${viewModel.passengerData.lastName}", fontSize = 12.sp)
        }
        OutlinedButton({ navController.navigate(Screen.Flight.route) }, Modifier.fillMaxWidth()) {
            Text("✈️ ${viewModel.flightData.from} → ${viewModel.flightData.to}", fontSize = 12.sp)
        }

        // ═══ المراحل ═══
        if (steps.any { it.status != StepStatus.PENDING }) {
            Text("المراحل:", fontWeight = FontWeight.Bold)
            steps.forEach { s -> StepRow(s) }
        }

        // ═══ PNR/SADAD ═══
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
                        Button({ clipboard.setText(AnnotatedString(sadad)) }, Modifier.fillMaxWidth()) {
                            Text("📋 نسخ SADAD")
                        }
                    }
                }
            }
        }

        // ═══ السجل ═══
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

        // ═══ Debug ═══
        if (debugEntries.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("🐞 Debug (${debugEntries.size})", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { viewModel.clearDebugLog() }) {
                        Text("مسح", fontSize = 11.sp)
                    }
                    TextButton(onClick = {
                        val all = debugEntries.joinToString("\n\n══════════════════\n\n") { e ->
                            val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                                .format(java.util.Date(e.timestamp))
                            buildString {
                                appendLine("[${e.source.label}] $time  ${e.method} ${e.url}")
                                appendLine("STATUS: ${e.statusCode ?: "-"}")
                                appendLine("TITLE : ${e.title}")
                                if (e.headers.isNotBlank()) { appendLine("HEADERS:"); appendLine(e.headers) }
                                appendLine("BODY:"); appendLine(e.body)
                            }
                        }
                        clipboard.setText(AnnotatedString(all))
                    }) { Text("📋 نسخ الكل", fontSize = 11.sp) }
                }
            }
            debugEntries.take(50).forEach { entry ->
                DebugCard(
                    entry = entry,
                    expanded = expandedDebugId == entry.id,
                    onToggle = { expandedDebugId = if (expandedDebugId == entry.id) null else entry.id }
                )
            }
        }
    }
}

@Composable
private fun StepRow(step: BookingStep) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
        containerColor = when (step.status) {
            StepStatus.RUNNING -> MaterialTheme.colorScheme.secondaryContainer
            StepStatus.SUCCESS -> MaterialTheme.colorScheme.primaryContainer
            StepStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
            StepStatus.SKIPPED -> MaterialTheme.colorScheme.tertiaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        }
    )) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when (step.status) {
                    StepStatus.SUCCESS -> Icons.Default.CheckCircle
                    StepStatus.FAILED -> Icons.Default.Error
                    StepStatus.SKIPPED -> Icons.Default.RemoveCircleOutline
                    else -> Icons.Default.RadioButtonUnchecked
                }, null,
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
            if (step.status == StepStatus.RUNNING) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun DebugCard(entry: DebugEntry, expanded: Boolean, onToggle: () -> Unit) {
    val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
        .format(java.util.Date(entry.timestamp))
    val accent = Color(entry.source.colorHex.toInt())
    Card(
        Modifier.fillMaxWidth().clickable { onToggle() },
        colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.08f)),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.55f))
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = accent, shape = MaterialTheme.shapes.small) {
                    Text(entry.source.label, color = Color.White, fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text("HTTP ${entry.statusCode ?: "-"}", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    color = when {
                        entry.statusCode == null -> MaterialTheme.colorScheme.onSurface
                        entry.statusCode in 200..299 -> Color(0xFF00C853)
                        entry.statusCode in 400..499 -> Color(0xFFD32F2F)
                        else -> Color(0xFFFF6F00)
                    })
                Spacer(Modifier.weight(1f))
                Text(time, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.height(4.dp))
            Text(entry.title, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                HorizontalDivider()
                Spacer(Modifier.height(6.dp))
                SelectionContainer {
                    Column {
                        if (entry.headers.isNotBlank()) {
                            Text("─ Headers ─", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = accent)
                            Text(entry.headers, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                            Spacer(Modifier.height(6.dp))
                        }
                        Text("─ Body ─", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = accent)
                        Text(entry.body, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            } else {
                Text("👆 اضغط للتفاصيل", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
