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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(navController: NavController, viewModel: FlightViewModel) {
    val context = LocalContext.current

    val steps by viewModel.steps.collectAsStateWithLifecycle()
    val isRunning by viewModel.isRunning.collectAsStateWithLifecycle()
    val pnr by viewModel.finalPnr.collectAsStateWithLifecycle()
    val sadad by viewModel.finalSadad.collectAsStateWithLifecycle()
    val searchResponse by viewModel.searchResponseText.collectAsStateWithLifecycle()
    val sessionStatus by viewModel.sessionStatus.collectAsStateWithLifecycle()
    val autoLoopActive by viewModel.autoLoopActive.collectAsStateWithLifecycle()
    val needsWebViewRefresh by viewModel.needsWebViewRefresh.collectAsStateWithLifecycle()
    val lastCycle by viewModel.autoLoopLastCycle.collectAsStateWithLifecycle()
    val sessionTimerSeconds by viewModel.sessionTimerSeconds.collectAsStateWithLifecycle()
    val jwtReady by viewModel.jwtReady.collectAsStateWithLifecycle()

    // 🆕 استدعاء تفاصيل الرحلة
    val bookedFlight = viewModel.lastBookedFlight

    var showWebView by remember { mutableStateOf(false) }
    var reeseTokenForWebView by remember { mutableStateOf<String?>(null) }

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

    Box(modifier = Modifier.fillMaxSize()) {
        if (showWebView && reeseTokenForWebView != null) {
            WebViewTokenCapture(
                reese84Token = reeseTokenForWebView!!,
                modifier = Modifier
                    .size(1.dp)
                    .alpha(0f)
                    .offset(x = (-1000).dp, y = (-1000).dp)
                    .zIndex(-1f),
                onSuccess = { jwt, sid ->
                    viewModel.onWebViewJwtCaptured(jwt, sid)
                    showWebView = false
                    reeseTokenForWebView = null
                }
            )
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
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "JWT: ${if (jwtReady) "✅" else "⏳"}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (jwtReady) Color(0xFF00C853) else Color(0xFFD32F2F)
                            )
                            Text(
                                "sid: ${if (viewModel.isSidReady()) "✅" else "⏳"}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (viewModel.isSidReady()) Color(0xFF00C853) else Color(0xFFD32F2F)
                            )
                            val minutes = sessionTimerSeconds / 60
                            val seconds = sessionTimerSeconds % 60
                            val timerText = if (sessionTimerSeconds > 0)
                                "⏱️ ${String.format("%02d:%02d", minutes, seconds)}"
                            else "⏱️ --:--"
                            Text(
                                timerText,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = when {
                                    sessionTimerSeconds <= 0L -> Color(0xFFD32F2F)
                                    sessionTimerSeconds < 60L -> Color(0xFFFF9800)
                                    else -> Color(0xFF00C853)
                                }
                            )
                            if (autoLoopActive) {
                                Text("🔁 $lastCycle", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    IconButton(onClick = { viewModel.refreshSession() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "تجديد Hyper", tint = Color(0xFFFFA726))
                    }
                }
            }

            // ═══ مستطيل رد الـ API ═══
            Card(
                Modifier.fillMaxWidth().height(200.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.85f)),
                border = BorderStroke(2.dp, Color.Green.copy(alpha = 0.7f))
            ) {
                Column(Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isRunning || autoLoopActive) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Color.Green)
                        } else {
                            Icon(Icons.Default.Info, null, tint = Color.Green, modifier = Modifier.size(14.dp))
                        }
                        Spacer(Modifier.width(6.dp))
                        Text("📡 API Response", color = Color.Green, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                        SelectionContainer {
                            Text(
                                searchResponse,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color.White,
                                lineHeight = 14.sp
                            )
                        }
                    }
                }
            }

            // ═══ مستطيل المرحلة الحالية ═══
            Card(
                Modifier.fillMaxWidth().height(100.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF00C853).copy(alpha = 0.15f)),
                border = BorderStroke(2.dp, Color(0xFF00C853))
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
                                StepStatus.RUNNING -> Icons.Default.PlayArrow
                                else -> Icons.Default.RadioButtonUnchecked
                            }, null, modifier = Modifier.size(36.dp),
                            tint = when (step.status) {
                                StepStatus.SUCCESS -> Color(0xFF00C853)
                                StepStatus.FAILED -> Color(0xFFD32F2F)
                                StepStatus.RUNNING -> Color(0xFF00C853)
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

            // ═══ 🆕 بطاقة التذكرة الكاملة (PNR/SADAD + تفاصيل الرحلة) ═══
            if (pnr.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
                    border = BorderStroke(3.dp, Color(0xFF00C853))
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        // تفاصيل الرحلة
                        if (bookedFlight != null) {
                            Text(
                                "✈️ ${bookedFlight.origin} → ${bookedFlight.destination}",
                                fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color.Black
                            )
                            Spacer(Modifier.height(4.dp))
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("الرحلة", fontSize = 11.sp, color = Color.DarkGray)
                                    Text(bookedFlight.flightNumber, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("الإقلاع", fontSize = 11.sp, color = Color.DarkGray)
                                    Text(bookedFlight.departureTime, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("الفئة", fontSize = 11.sp, color = Color.DarkGray)
                                    Text(bookedFlight.fareFamily, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            HorizontalDivider(color = Color.Black.copy(alpha = 0.2f), thickness = 1.dp)
                            Spacer(Modifier.height(12.dp))
                        }

                        // PNR و SADAD
                        Text("🆔 PNR: $pnr", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.Black)
                        Text("💳 SADAD: $sadad", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.Black)
                        
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = {
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("SADAD", sadad))
                            },
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00C853))
                        ) {
                            Text("📋 نسخ SADAD", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                    }
                }
            }

            // ═══ زر بدء/إيقاف البحث ═══
            Button(
                onClick = {
                    if (autoLoopActive) viewModel.stopAutoLoop()
                    else viewModel.startAutoLoop()
                },
                modifier = Modifier.fillMaxWidth().height(64.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (autoLoopActive) Color(0xFFD32F2F) else Color(0xFF9C27B0)
                )
            ) {
                Text(
                    if (autoLoopActive) "⏹️ إيقاف البحث"
                    else "🔁 بدء البحث",
                    fontWeight = FontWeight.Bold, fontSize = 15.sp
                )
            }

            // ═══ وضع المراقبة ═══
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
        }
    }
}
