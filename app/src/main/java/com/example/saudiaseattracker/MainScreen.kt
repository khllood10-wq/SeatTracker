package com.example.saudiaseattracker

import android.app.Activity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
    val pnr by viewModel.finalPnr.collectAsStateWithLifecycle()
    val sadad by viewModel.finalSadad.collectAsStateWithLifecycle()
    val searchResponse by viewModel.searchResponseText.collectAsStateWithLifecycle()
    val jsLogText by viewModel.jsLogText.collectAsStateWithLifecycle()
    val sessionStatus by viewModel.sessionStatus.collectAsStateWithLifecycle()
    val autoLoopActive by viewModel.autoLoopActive.collectAsStateWithLifecycle()
    val needsWebViewRefresh by viewModel.needsWebViewRefresh.collectAsStateWithLifecycle()
    val autoCaptureMode by viewModel.autoCaptureMode.collectAsStateWithLifecycle()
    val lastCycle by viewModel.autoLoopLastCycle.collectAsStateWithLifecycle()

    var showWebView by remember { mutableStateOf(false) }
    var reeseTokenForWebView by remember { mutableStateOf<String?>(null) }
    var jwtMinutesLeft by remember { mutableLongStateOf(0L) }
    var hyperSecondsLeft by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        if (viewModel.hasToken()) viewModel.markSessionReady()
        else viewModel.markSessionPreparing()
    }

    LaunchedEffect(needsWebViewRefresh) {
        if (needsWebViewRefresh && reeseTokenForWebView == null && !showWebView) {
            val t = viewModel.showWebViewCapture()
            if (t != null) { reeseTokenForWebView = t; showWebView = true }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            jwtMinutesLeft = viewModel.jwtRemainingMinutes()
            val hyperExpiry = viewModel.sessionExpiryMs()
            hyperSecondsLeft = ((hyperExpiry - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
            kotlinx.coroutines.delay(1000)
        }
    }

    if (showWebView && reeseTokenForWebView != null) {
        WebViewTokenCapture(
            reese84Token = reeseTokenForWebView!!,
            autoMode = autoCaptureMode,
            onSuccess = { jwt, sid ->
                viewModel.onWebViewJwtCaptured(jwt, sid)
                showWebView = false; reeseTokenForWebView = null
            },
            onLog = { msg -> viewModel.appendJsLog(msg) },
            onCancel = { showWebView = false; reeseTokenForWebView = null; viewModel.dismissWebView() }
        )
        return
    }

    val currentStep = steps.firstOrNull { it.status == StepStatus.RUNNING }
        ?: steps.firstOrNull { it.status == StepStatus.FAILED }
        ?: steps.lastOrNull { it.status == StepStatus.SUCCESS }
        ?: steps.first()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ═══ شريط الحالة العلوي ═══
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(sessionStatus, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("JWT: ${if (jwtMinutesLeft > 0) "${jwtMinutesLeft}د" else "❌"}", fontSize = 11.sp)
                        Text("reese84: ${hyperSecondsLeft}s", fontSize = 11.sp)
                        Text("sid: ${if (viewModel.isSidReady()) "✅" else "⏳"}", fontSize = 11.sp)
                        if (autoLoopActive) Text("🔁 $lastCycle", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
                IconButton(onClick = { viewModel.refreshSession() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "تجديد Hyper", tint = Color(0xFFFFA726))
                }
            }
        }

        // ═══ مستطيل حالة البحث ═══
        Card(
            Modifier.fillMaxWidth().heightIn(min = 100.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isRunning || autoLoopActive) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("حالة البحث والرحلات", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    searchResponse,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (searchResponse.contains("✅")) Color(0xFF00C853) else MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // ═══ مستطيل JS Logs ═══
        Card(
            Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 150.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.5f)),
            border = BorderStroke(1.dp, Color.Green.copy(alpha = 0.5f))
        ) {
            Column(Modifier.padding(8.dp).verticalScroll(rememberScrollState())) {
                Text("🌐 JS Logs (WebView)", color = Color.Green, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                Text(
                    if (jsLogText.isEmpty()) "بانتظار بدء الاتصال..." else jsLogText,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color.White
                )
            }
        }

        // ═══ مستطيل المرحلة الحالية ═══
        Card(
            Modifier.fillMaxWidth().height(110.dp),
            colors = CardDefaults.cardColors(
                containerColor = when (currentStep.status) {
                    StepStatus.RUNNING -> Color(0xFF2196F3).copy(alpha = 0.15f)
                    StepStatus.SUCCESS -> Color(0xFF00C853).copy(alpha = 0.15f)
                    StepStatus.FAILED -> Color(0xFFD32F2F).copy(alpha = 0.15f)
                    else -> MaterialTheme.colorScheme.surface
                }
            ),
            border = BorderStroke(2.dp, when (currentStep.status) {
                StepStatus.RUNNING -> Color(0xFF2196F3)
                StepStatus.SUCCESS -> Color(0xFF00C853)
                StepStatus.FAILED -> Color(0xFFD32F2F)
                else -> Color.Transparent
            })
        ) {
            AnimatedContent(
                targetState = currentStep,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "step_transition"
            ) { step ->
                Row(Modifier.fillMaxSize().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        when (step.status) {
                            StepStatus.SUCCESS -> Icons.Default.CheckCircle
                            StepStatus.FAILED -> Icons.Default.Error
                            StepStatus.RUNNING -> Icons.Default.Refresh
                            else -> Icons.Default.RadioButtonUnchecked
                        }, null, modifier = Modifier.size(36.dp),
                        tint = when (step.status) {
                            StepStatus.SUCCESS -> Color(0xFF00C853)
                            StepStatus.FAILED -> Color(0xFFD32F2F)
                            StepStatus.RUNNING -> Color(0xFF2196F3)
                            else -> Color.Gray
                        }
                    )
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(step.title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        if (step.detail.isNotEmpty()) {
                            Text(step.detail, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }

        // ═══ PNR/SADAD ═══
        if (pnr.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
                border = BorderStroke(3.dp, Color(0xFF00C853))
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🆔 PNR: $pnr", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.Black)
                    Text("💳 SADAD: $sadad", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.Black)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { 
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("SADAD", sadad))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00C853))
                    ) {
                        Text("📋 نسخ SADAD", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // ═══ الأزرار ═══
        Button(
            onClick = {
                if (autoLoopActive) viewModel.stopAutoLoop()
                else viewModel.startAutoLoop()
            },
            modifier = Modifier.fillMaxWidth().height(64.dp),
            colors = ButtonDefaults.buttonColors(containerColor = if (autoLoopActive) Color(0xFFD32F2F) else Color(0xFF9C27B0))
        ) {
            Text(
                if (autoLoopActive) "⏹️ إيقاف البحث"
                else "🔁 بدء البحث",
                fontWeight = FontWeight.Bold, fontSize = 15.sp
            )
        }

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

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("👁️ وضع المراقبة", fontWeight = FontWeight.Bold)
                    Text(if (viewModel.monitoringMode) "✅ يصل للدفع بدون إتمام" else "معطّل", fontSize = 11.sp)
                }
                Switch(viewModel.monitoringMode, { viewModel.monitoringMode = it }, enabled = !isRunning)
            }
        }
    }
}
