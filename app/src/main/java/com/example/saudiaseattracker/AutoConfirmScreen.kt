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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
    val prefs = context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
    val sessionId = prefs.getString("session_id", "") ?: ""
    val xDToken = prefs.getString("xd_token", "") ?: ""
    val authToken = prefs.getString("auth_token", "") ?: ""
    val cookies = prefs.getString("dynamic_cookies", "") ?: ""

    var status by remember { mutableStateOf("⏳ جاري تحميل الصفحة (WebView معزول)...") }
    var progress by remember { mutableStateOf(0) }
    var patchSent by remember { mutableStateOf(false) }
    var patchBodySize by remember { mutableStateOf(0) }
    var apiResultCode by remember { mutableStateOf(0) }
    var apiResultBody by remember { mutableStateOf("") }
    var apiSuccess by remember { mutableStateOf(false) }

    val bridge = remember {
        object {
            @JavascriptInterface
            fun onPatchRequest(bodyLength: Int) {
                Handler(Looper.getMainLooper()).post {
                    patchBodySize = bodyLength
                    status = "📤 تم إرسال PATCH من WebView (حجم body = $bodyLength حرف)..."
                }
            }

            @JavascriptInterface
            fun onPatchResponse(statusCode: Int, responseBody: String) {
                Handler(Looper.getMainLooper()).post {
                    // حفظ الكوكيز الجديدة للجلسة
                    val cm = CookieManager.getInstance()
                    val sauCookies = cm.getCookie("https://www.saudia.com") ?: ""
                    val dapiCookies = cm.getCookie("https://dapi.saudia.com") ?: ""
                    val merged = listOf(sauCookies, dapiCookies)
                        .filter { it.isNotBlank() }
                        .joinToString("; ")
                        .replace(Regex(";+"), ";")
                        .trim()
                        .trimStart(';', ' ')

                    prefs.edit().putString("dynamic_cookies", merged).apply()

                    apiResultCode = statusCode
                    apiResultBody = responseBody
                    patchSent = true
                    apiSuccess = (statusCode == 200)

                    if (statusCode == 200) {
                        status = "🎉 نجح! السيرفر قبل البيانات."
                    } else {
                        status = "❌ فشل: HTTP $statusCode"
                    }
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

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(Modifier.padding(12.dp)) {
                Text("🤖 تأكيد تلقائي (WebView معزول)", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(status, style = MaterialTheme.typography.bodySmall)
                if (progress in 1..99 && !patchSent) {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                }
                if (patchBodySize > 0) {
                    Text("حجم البيانات المشفرة: $patchBodySize حرف", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
                Text("cartId: $cartId", fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            }
        }

        // ── WebView معزول (بارتفاع محدد لمنع أي تداخل) ──
        Box(modifier = Modifier.fillMaxWidth().height(500.dp).padding(horizontal = 8.dp)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        // عزل تام وتنظيف
                        clearCache(true)
                        clearHistory()
                        clearFormData()
                        clearSslPreferences()

                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
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
                                                var bodySize = (init && init.body) ? init.body.length : 0;
                                                SaudiaBridge.onPatchRequest(bodySize);
                                                var promise = origFetch.apply(this, arguments);
                                                promise.then(function(response) {
                                                    response.clone().text().then(function(text) {
                                                        SaudiaBridge.onPatchResponse(response.status, text);
                                                    });
                                                });
                                                return promise;
                                            }
                                            return origFetch.apply(this, arguments);
                                        };
                                    })();
                                """.trimIndent(), null)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                if (url == null || patchSent) return
                                if (url.contains("passengerDetails")) {
                                    status = "📝 الفورم جاهز. تعبئة تلقائية..."
                                    // ملء الفورم
                                    view?.postDelayed({
                                        view.evaluateJavascript("""
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
                                                        setTimeout(function() {
                                                            var btns = document.querySelectorAll('button');
                                                            for (var i = 0; i < btns.length; i++) {
                                                                var t = (btns[i].innerText || '').trim();
                                                                if (t.indexOf('تأكيد بيانات') > -1 || t.indexOf('تأكيد المسافر') > -1) {
                                                                    btns[i].click();
                                                                    return;
                                                                }
                                                            }
                                                            for (var j = 0; j < btns.length; j++) {
                                                                var t2 = (btns[j].innerText || '').trim();
                                                                if (t2.indexOf('تأكيد') > -1 && t2.indexOf('انتقال') === -1 && t2.indexOf('دفع') === -1) {
                                                                    btns[j].click();
                                                                    return;
                                                                }
                                                            }
                                                        }, 1500);
                                                    }
                                                    if (attempts > 30) clearInterval(iv);
                                                }, 500);
                                            })();
                                        """.trimIndent(), null)
                                    }, 5000)
                                }
                            }
                        }

                        loadUrl("https://www.saudia.com/ar-SA/booking/passengerDetails?cartId=$cartId")
                    }
                }
            )
        }

        // ── نتيجة الطلب ──
        if (patchSent) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                border = BorderStroke(2.dp, if (apiSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text("HTTP $apiResultCode", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = if (apiSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(4.dp))
                    SelectionContainer {
                        Text(apiResultBody.take(400), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    }
                }
            }
        }

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

        if (!patchSent) {
            OutlinedButton(
                onClick = { navController.popBackStack() },
                modifier = Modifier.fillMaxWidth().padding(8.dp)
            ) { Text("إلغاء والرجوع") }
        }
    }
}
