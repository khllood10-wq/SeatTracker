package com.example.saudiaseattracker

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController

@SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
@Composable
fun AutoConfirmScreen(navController: NavController, viewModel: FlightViewModel) {
    val context = LocalContext.current
    val cartId = viewModel.cartId.collectAsStateWithLifecycle().value
    val apiResult = viewModel.passengerConfirmResponse.collectAsStateWithLifecycle().value.orEmpty()
    val prefs = context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
    val sessionId = prefs.getString("session_id", "") ?: ""
    val xDToken = prefs.getString("xd_token", "") ?: ""
    val authToken = prefs.getString("auth_token", "") ?: ""
    val cookies = prefs.getString("dynamic_cookies", "") ?: ""

    var status by remember { mutableStateOf("⏳ تحميل الصفحة...") }
    var progress by remember { mutableStateOf(0) }
    var capturedKey by remember { mutableStateOf<String?>(null) }
    var capturedBody by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    var apiSuccess by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    val bridge = remember {
        object {
            @JavascriptInterface
            fun onPayloadCaptured(key: String, body: String) {
                Handler(Looper.getMainLooper()).post {
                    capturedKey = key
                    capturedBody = body
                    status = "✅ التشفير تم اعتراضه (key=${key.length}، body=${body.length})"
                    // إخفاء WebView فوراً — لم نعد نحتاجه
                    webViewRef?.visibility = WebView.INVISIBLE
                }
            }
            @JavascriptInterface
            fun onFormFilled() {
                Handler(Looper.getMainLooper()).post {
                    status = "📝 تم ملء الفورم، جاري الضغط على 'تأكيد بيانات المسافر'..."
                }
            }
            @JavascriptInterface
            fun onLog(msg: String) {
                android.util.Log.d("AutoConfirm", msg)
            }
        }
    }

    if (cartId.isNullOrEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("❌ لا يوجد cartId")
        }
        return
    }

    // إرسال تلقائي بمجرد التقاط التشفير
    LaunchedEffect(capturedKey, capturedBody) {
        if (capturedKey != null && capturedBody != null && !sending && !apiSuccess) {
            sending = true
            status = "📤 جاري إرسال PATCH عبر API..."
            val ok = viewModel.sendCapturedPayload(capturedKey!!, capturedBody!!, cartId)
            sending = false
            if (ok) {
                apiSuccess = true
                status = "🎉 نجح! السيرفر قبل البيانات"
            } else {
                status = "❌ فشل — راجع النتيجة أدناه"
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Card(modifier = Modifier.fillMaxWidth().padding(8.dp),
             colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(12.dp)) {
                Text("🕵️ التقاط التشفير + إرسال API", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(status, style = MaterialTheme.typography.bodySmall)
                if (progress in 1..99 && capturedKey == null) {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                }
                Text("cartId: $cartId", fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            }
        }

        // WebView (يُصبح غير مرئي بعد التقاط التشفير)
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { ctx ->
                WebView(ctx).apply {
                    webViewRef = this
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    // UA جوال حقيقي — حل مشكلة Imperva
                    settings.userAgentString = "Mozilla/5.0 (Linux; Android 14; SM-S918B) " +
                            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                    addJavascriptInterface(bridge, "SaudiaBridge")

                    val cm = CookieManager.getInstance()
                    cm.setAcceptCookie(true)
                    cm.setAcceptThirdPartyCookies(this, true)
                    if (cookies.isNotEmpty()) {
                        listOf("https://www.saudia.com", "https://dapi.saudia.com").forEach {
                            cm.setCookie(it, cookies)
                        }
                        cm.flush()
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) { progress = newProgress }
                    }

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            // (1) حقن التوكنات
                            // (2) Hook fetch لاعتراض PATCH وإيقافه
                            view?.evaluateJavascript("""
                                try {
                                    localStorage.setItem('saa-session-id', '$sessionId');
                                    localStorage.setItem('x-d-token', '$xDToken');
                                    localStorage.setItem('authorization', 'Bearer $authToken');
                                } catch(e){}
                                (function() {
                                    if (window.__saudiaHooked) return;
                                    window.__saudiaHooked = true;
                                    var origFetch = window.fetch;
                                    window.fetch = function(input, init) {
                                        var url = typeof input === 'string' ? input : (input && input.url);
                                        var method = ((init && init.method) || 'GET').toUpperCase();
                                        if (url && url.indexOf('/travelers/web') > -1 && method === 'PATCH') {
                                            try {
                                                var p = JSON.parse(init.body);
                                                if (p && p.key && p.body) {
                                                    SaudiaBridge.onPayloadCaptured(p.key, p.body);
                                                    SaudiaBridge.onLog('BLOCKED: payload captured');
                                                    // إيقاف الطلب نهائياً
                                                    return new Promise(function() {});
                                                }
                                            } catch(e) { SaudiaBridge.onLog('parse: '+e.message); }
                                        }
                                        return origFetch.apply(this, arguments);
                                    };
                                })();
                            """.trimIndent(), null)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            if (url == null) return
                            if (url.contains("passengerDetails")) {
                                status = "📄 الصفحة جاهزة — انتظار 6 ثوانٍ لتحميل الفورم..."
                                // (3) انتظار ثم ملء الفورم والضغط على "تأكيد بيانات المسافر"
                                view?.postDelayed({ fillAndClickConfirm(view) }, 6000)
                            }
                        }
                    }

                    loadUrl("https://www.saudia.com/ar-SA/booking/passengerDetails?cartId=$cartId")
                }
            }
        )

        // نتيجة الـ API
        if (apiResult.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth().padding(8.dp),
                 colors = CardDefaults.cardColors(
                     containerColor = if (apiSuccess) MaterialTheme.colorScheme.primaryContainer
                                      else MaterialTheme.colorScheme.errorContainer
                 )) {
                Text(
                    "HTTP ${if (apiSuccess) "✅ 200" else "❌"}\n\n${apiResult.take(600)}",
                    modifier = Modifier.padding(12.dp),
                    fontFamily = FontFamily.Monospace, fontSize = 10.sp
                )
            }
        }

        // زر الانتقال للدفع (يظهر فقط بعد نجاح PATCH)
        if (apiSuccess) {
            Button(
                onClick = {
                    navController.navigate(Screen.Payment.route) {
                        popUpTo(Screen.Passengers.route) { inclusive = false }
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(8.dp).height(60.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("➡️ الانتقال لصفحة الدفع", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }

        if (capturedKey == null && !sending) {
            OutlinedButton(
                onClick = {
                    status = "🔄 إعادة المحاولة..."
                    webViewRef?.reload()
                },
                modifier = Modifier.fillMaxWidth().padding(8.dp)
            ) { Text("🔄 إعادة تحميل") }
        }
    }
}

/**
 * يملأ الفورم ويضغط زر "تأكيد بيانات المسافر" فقط.
 * لا يضغط "الانتقال لصفحة الدفع" — هذا يتم عبر API في Kotlin.
 */
private fun fillAndClickConfirm(view: WebView) {
    val js = """
        (function() {
            function setVal(name, val) {
                var el = document.querySelector('[formcontrolname="'+name+'"]') || document.querySelector('[name="'+name+'"]');
                if (!el) return false;
                var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
                setter.call(el, val);
                el.dispatchEvent(new Event('input', {bubbles:true}));
                el.dispatchEvent(new Event('change', {bubbles:true}));
                el.dispatchEvent(new Event('blur', {bubbles:true}));
                return true;
            }
            var attempts = 0;
            var iv = setInterval(function() {
                attempts++;
                var fnOk = setVal('firstName', 'Khaled');
                var lnOk = setVal('lastName', 'Otudi');
                setVal('dateOfBirth', '1990-05-10');
                setVal('nationalId', '1071580342');
                setVal('documentNumber', '1071580342');
                if (fnOk && lnOk) {
                    clearInterval(iv);
                    SaudiaBridge.onFormFilled();
                    setTimeout(function() {
                        var btns = document.querySelectorAll('button');
                        for (var i = 0; i < btns.length; i++) {
                            var t = (btns[i].innerText || '').trim();
                            // نضغط فقط "تأكيد بيانات المسافر" — لا "الانتقال لصفحة الدفع"
                            if (t.indexOf('تأكيد بيانات') > -1 || t.indexOf('تأكيد المسافر') > -1) {
                                SaudiaBridge.onLog('CLICKED: '+t);
                                btns[i].click();
                                return;
                            }
                        }
                        // احتياطي: أي زر فيه "تأكيد" (لكن ليس "الانتقال")
                        for (var j = 0; j < btns.length; j++) {
                            var t2 = (btns[j].innerText || '').trim();
                            if (t2.indexOf('تأكيد') > -1 && t2.indexOf('انتقال') === -1 && t2.indexOf('دفع') === -1) {
                                SaudiaBridge.onLog('CLICKED (fallback): '+t2);
                                btns[j].click();
                                return;
                            }
                        }
                        SaudiaBridge.onLog('NO BUTTON FOUND');
                    }, 1500);
                }
                if (attempts > 30) {
                    clearInterval(iv);
                    SaudiaBridge.onLog('GAVE UP - fields not found');
                }
            }, 500);
        })();
    """.trimIndent()
    view.evaluateJavascript(js, null)
}
