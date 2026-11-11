package com.example.saudiaseattracker

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.ui.viewinterop.AndroidView
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
    val sessionReady by viewModel.sessionReady.collectAsStateWithLifecycle()
    val sessionStatus by viewModel.sessionStatus.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(Unit) {
        if (viewModel.hasToken()) {
            viewModel.markSessionReady()
        } else {
            viewModel.markSessionPreparing()
        }
    }

    val paymentStep = steps.find { it.id == "payment" }
    val paymentStatus = paymentStep?.status ?: StepStatus.PENDING

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("✈️ حجز تلقائي", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

            // ── بانر الجلسة ──
            if (!sessionReady) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Row(
                        Modifier.padding(10.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("⏳ تجهيز الجلسة", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text(sessionStatus, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                }
            }

            // ── وضع المراقبة ──
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
                        Text("👁️ وضع المراقبة", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(
                            if (viewModel.monitoringMode)
                                "✅ مفعّل: يصل للدفع بدون إتمام"
                            else
                                "معطّل: حجز كامل",
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

            // ── ملخص المسافر ──
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

            // ── ملخص الرحلة ──
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
                enabled = !isRunning && sessionReady &&
                        viewModel.passengerData.isValid() && viewModel.flightData.isValid(),
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
                } else if (!sessionReady) {
                    Text("⏳ انتظر تجهيز الجلسة...", fontWeight = FontWeight.Bold)
                } else {
                    Text(
                        if (viewModel.monitoringMode) "👁️ بدء المراقبة"
                        else "🚀 بدء الحجز التلقائي",
                        fontWeight = FontWeight.Bold, fontSize = 16.sp
                    )
                }
            }

            // ⭐⭐⭐ زر تحدي التوكن ⭐⭐⭐
            OutlinedButton(
                onClick = { navController.navigate(Screen.TokenChallenge.route) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(
                    "🎯 تحدي: التقاط التوكن بدون WebView",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // ── Stepper ──
            if (steps.any { it.status != StepStatus.PENDING }) {
                Text("مراحل الحجز:", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                steps.forEach { step -> StepRow(step) }
            }

            // ── النتيجة ──
            if (pnr.isNotEmpty()) {
                when (paymentStatus) {
                    StepStatus.SUCCESS -> ResultCard(
                        title = "🎉 تم الحجز الكامل!",
                        titleColor = Color(0xFF00C853),
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        borderColor = Color(0xFF00C853),
                        pnr = pnr, sadad = sadad, clipboard = clipboard,
                        note = "ادفع رقم SADAD في البنك — يصلك الإيميل + التذكرة تلقائياً"
                    )
                    StepStatus.SKIPPED -> ResultCard(
                        title = "👁️ وضع المراقبة — وصلنا للدفع",
                        titleColor = MaterialTheme.colorScheme.tertiary,
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        borderColor = MaterialTheme.colorScheme.tertiary,
                        pnr = pnr, sadad = sadad, clipboard = clipboard,
                        note = "لم يُرسل الدفع — PNR يلغى تلقائياً بعد ~5 ساعات"
                    )
                    StepStatus.FAILED -> ResultCard(
                        title = "⚠️ فشل الدفع",
                        titleColor = MaterialTheme.colorScheme.error,
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        borderColor = MaterialTheme.colorScheme.error,
                        pnr = pnr, sadad = sadad, clipboard = clipboard,
                        note = "قد يكون لديك حجز سابق معلق — ادفعه أو انتظر انتهاء المهلة"
                    )
                    else -> Card(
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

            // ── السجل ──
            if (logs.isNotEmpty()) {
                Text("📝 السجل:", fontWeight = FontWeight.Bold, fontSize = 13.sp)
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

        // WebView مخفي
        if (!sessionReady) {
            HiddenSessionWebView(viewModel = viewModel)
        }
    }
}

// ══════════════════════════════════════════════════════════════
// WebView مخفي
// ══════════════════════════════════════════════════════════════
@SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
@Composable
private fun HiddenSessionWebView(viewModel: FlightViewModel) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
    var captured by remember { mutableStateOf(false) }

    val bridge = remember {
        object {
            @JavascriptInterface
            fun onTokenCaptured(auth: String, xD: String, sid: String, ua: String) {
                if (captured) return
                captured = true
                viewModel.saveTokens(auth, xD, sid, ua)
                try {
                    val cm = CookieManager.getInstance()
                    val sau = cm.getCookie("https://www.saudia.com") ?: ""
                    val dapi = cm.getCookie("https://dapi.saudia.com") ?: ""
                    val merged = listOf(sau, dapi)
                        .filter { it.isNotBlank() }
                        .joinToString("; ")
                        .replace(Regex(";+"), ";")
                        .trim()
                        .trimStart(';', ' ')
                    if (merged.isNotBlank()) {
                        prefs.edit().putString("dynamic_cookies", merged).apply()
                    }
                } catch (e: Exception) {
                    android.util.Log.e("Session", "Cookie save: ${e.message}")
                }
                viewModel.markSessionReady()
            }
            @JavascriptInterface
            fun onLog(msg: String) { android.util.Log.d("Session", msg) }
        }
    }

    AndroidView(
        modifier = Modifier.size(1.dp),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.userAgentString =
                    "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                addJavascriptInterface(bridge, "SaudiaBridge")

                val cm = CookieManager.getInstance()
                cm.setAcceptCookie(true)
                cm.setAcceptThirdPartyCookies(this, true)
                prefs.getString("dynamic_cookies", "")?.takeIf { it.isNotBlank() }?.let { c ->
                    listOf("https://www.saudia.com", "https://dapi.saudia.com").forEach { u ->
                        cm.setCookie(u, c)
                    }
                    cm.flush()
                }

                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        view?.evaluateJavascript(getSessionHookJS(), null)
                    }
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        view?.evaluateJavascript("""
                            setTimeout(function() {
                                try {
                                    var auth = localStorage.getItem('authorization') ||
                                               localStorage.getItem('token');
                                    var xD = localStorage.getItem('x-d-token') || '';
                                    var sid = localStorage.getItem('saa-session-id') || '';
                                    if (auth && auth.indexOf('Bearer') > -1) {
                                        SaudiaBridge.onTokenCaptured(auth, xD, sid, navigator.userAgent);
                                    }
                                } catch(e) {}
                            }, 2500);
                        """.trimIndent(), null)
                    }
                }

                loadUrl("https://www.saudia.com/ar-SA")
            }
        }
    )
}

private fun getSessionHookJS(): String = """
    (function() {
        if (window.__sessionHooked) return;
        window.__sessionHooked = true;
        function getHeader(h, name) {
            if (!h) return '';
            if (typeof h.get === 'function') return h.get(name) || h.get(name.toLowerCase()) || '';
            for (var k in h) if (k.toLowerCase() === name.toLowerCase()) return h[k] || '';
            return '';
        }
        var origFetch = window.fetch;
        window.fetch = function(input, init) {
            try {
                init = init || {};
                var h = init.headers || (input && input.headers);
                var auth = getHeader(h, 'Authorization');
                if (auth && auth.indexOf('Bearer') > -1) {
                    SaudiaBridge.onTokenCaptured(
                        auth,
                        getHeader(h, 'x-d-token'),
                        getHeader(h, 'saa-session-id'),
                        navigator.userAgent
                    );
                }
            } catch(e) {}
            return origFetch.apply(this, arguments);
        };
        var origOpen = XMLHttpRequest.prototype.open;
        var origSet = XMLHttpRequest.prototype.setRequestHeader;
        XMLHttpRequest.prototype.open = function() {
            this.__h = {};
            return origOpen.apply(this, arguments);
        };
        XMLHttpRequest.prototype.setRequestHeader = function(k, v) {
            try {
                if (!this.__h) this.__h = {};
                this.__h[k.toLowerCase()] = v;
                if (k.toLowerCase() === 'authorization' && v && v.indexOf('Bearer') > -1) {
                    SaudiaBridge.onTokenCaptured(
                        v,
                        this.__h['x-d-token'] || '',
                        this.__h['saa-session-id'] || '',
                        navigator.userAgent
                    );
                }
            } catch(e) {}
            return origSet.apply(this, arguments);
        };
    })();
""".trimIndent()

// ══════════════════════════════════════════════════════════════
// عناصر مساعدة
// ══════════════════════════════════════════════════════════════
@Composable
private fun ResultCard(
    title: String,
    titleColor: Color,
    containerColor: Color,
    borderColor: Color,
    pnr: String,
    sadad: String,
    clipboard: androidx.compose.ui.platform.ClipboardManager,
    note: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(3.dp, borderColor)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = titleColor)
            Spacer(Modifier.height(8.dp))
            Row {
                Text("PNR: ", fontWeight = FontWeight.Bold)
                Text(pnr, fontFamily = FontFamily.Monospace, fontSize = 18.sp, color = titleColor)
            }
            if (sadad.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row {
                    Text("SADAD: ", fontWeight = FontWeight.Bold)
                    Text(sadad, fontFamily = FontFamily.Monospace, fontSize = 18.sp, color = titleColor)
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { clipboard.setText(AnnotatedString(sadad)) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("📋 نسخ SADAD") }
            }
            Spacer(Modifier.height(10.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Text(note, modifier = Modifier.padding(10.dp), fontSize = 11.sp)
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
                    else -> Icons.Default.RadioButtonUnchecked
                },
                contentDescription = null,
                tint = when (step.status) {
                    StepStatus.SUCCESS -> Color(0xFF00C853)
                    StepStatus.FAILED -> Color(0xFFD32F2F)
                    StepStatus.SKIPPED -> MaterialTheme.colorScheme.tertiary
                    StepStatus.RUNNING -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
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
