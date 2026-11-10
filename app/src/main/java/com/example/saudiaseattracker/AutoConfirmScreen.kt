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
    val prefs = context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
    val sessionId = prefs.getString("session_id", "") ?: ""
    val xDToken = prefs.getString("xd_token", "") ?: ""
    val authToken = prefs.getString("auth_token", "") ?: ""
    val cookies = prefs.getString("dynamic_cookies", "") ?: ""

    var status by remember { mutableStateOf("⏳ تحميل الصفحة...") }
    var progress by remember { mutableStateOf(0) }
    var apiSuccess by remember { mutableStateOf(false) }
    var apiResultCode by remember { mutableStateOf(0) }
    var apiResultBody by remember { mutableStateOf("") }
    var patchSent by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    val bridge = remember {
        object {
            @JavascriptInterface
            fun onPatchResponse(statusCode: Int, responseBody: String) {
                Handler(Looper.getMainLooper()).post {
                    // حفظ كوكيز WebView (مهمة للدفع)
                    val cm = CookieManager.getInstance()
                    val sauCookies = cm.getCookie("https://www.saudia.com") ?: ""
                    val dapiCookies = cm.getCookie("https://dapi.saudia.com") ?: ""
                    val merged = listOf(sauCookies, dapiCookies)
                        .filter { it.isNotBlank() }
                        .joinToString("; ")
                        .replace(Regex(";+"), ";")
                        .trim()
                        .trimStart(';', ' ')

                    prefs.edit()
                        .putString("dynamic_cookies", merged)
                        .apply()

                    apiResultCode = statusCode
                    apiResultBody = responseBody
                    patchSent = true

                    if (statusCode == 200) {
                        apiSuccess = true
                        status = "🎉 نجح! السيرفر قبـل بيانات المسافر (كو كيز محدّثة لـ ${merged.length}ch)"
                        webViewRef?.visibility = WebView.INVISIBLE
                    } else {
                        status = "❌ فشل: HTTP $statusCode"
                    }
                }
            }

            @JavascriptInterface
            fun onFormFilled() {
                Handler(Looper.getMainLooper()).post {
                    status = "📝 الفورم مُلئ. جاري الضغط على 'تأكيد بيانات المسافر'..."
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

    Column(modifier = Modifier.fillMaxSize()) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(Modifier.padding(12.dp)) {
                Text("🤖 تأكيد تلقائي — WebView يرسل، نلتقط الرد فقط", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(status, style = MaterialTheme.typography.bodySmall)
                if (progress in 1..99 && !patchSent) {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                }
                Text("cartId: $cartId", fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            }
        }

        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { ctx ->
                WebView(ctx).apply {
                    webViewRef = this
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
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            progress = newProgress
                        }
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
                                    
                                    // ⭐ نمرر الطلب ولا نمنعه، فقط نلتقط الـ Response
                                    var origFetch = window.fetch;
                                    window.fetch = function(input, init) {
                                        var url = typeof input === 'string' ? input : (input && input.url);
                                        var method = ((init && init.method) || 'GET').toUpperCase();
                                        if (url && url.indexOf('/travelers/web') > -1 && method === 'PATCH') {
                                            SaudiaBridge.onLog('PATCH letting through: '+url);
                                            var promise = origFetch.apply(this, arguments);
                                            promise.then(function(response) {
                                                response.clone().text().then(function(text) {
                                                    SaudiaBridge.onPatchResponse(response.status, text);
                                                });
                                            }).catch(function(e) {
                                                SaudiaBridge.onLog('PATCH err: '+e.message);
                                            });
                                            return promise;
                                        }
                                        return origFetch.apply(this, arguments);
                                    };

                                    // دعم XHR أيضاً (احتياط)
                                    var origOpen = XMLHttpRequest.prototype.open;
                                    var origSend = XMLHttpRequest.prototype.send;
                                    XMLHttpRequest.prototype.open = function(m, u) {
                                        this.__m = (m||'').toUpperCase();
                                        this.__u = u;
                                        return origOpen.apply(this, arguments);
                                    };
                                    XMLHttpRequest.prototype.send = function() {
                                        var self = this;
                                        if (self.__u && self.__u.indexOf('/travelers/web') > -1 && self.__m === 'PATCH') {
                                            self.addEventListener('load', function() {
                                                try { SaudiaBridge.onPatchResponse(self.status, self.responseText); } catch(e){}
                                            });
                                        }
                                        return origSend.apply(this, arguments);
                                    };
                                })();
                            """.trimIndent(), null)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            if (url == null || patchSent) return
                            if (url.contains("passengerDetails")) {
                                status = "📄 انتظار تحميل الفورم (6 ثوانٍ)..."
                                view?.postDelayed({ fillAndClickConfirm(view) }, 6000)
                            }
                        }
                    }

                    loadUrl("https://www.saudia.com/ar-SA/booking/passengerDetails?cartId=$cartId")
                }
            }
        )

        if (patchSent) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (apiSuccess) MaterialTheme.colorScheme.primaryContainer
                                     else MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    "HTTP ${apiResultCode}\n\n${apiResultBody.take(700)}",
                    modifier = Modifier.padding(12.dp),
                    fontFamily = FontFamily.Monospace, fontSize = 10.sp
                )
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
                onClick = {
                    status = "🔄 إعادة المحاولة..."
                    webViewRef?.reload()
                },
                modifier = Modifier.fillMaxWidth().padding(8.dp)
            ) { Text("🔄 إعادة تحميل") }
        }
    }
}

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
                            if (t.indexOf('تأكيد بيانات') > -1 || t.indexOf('تأكيد المسافر') > -1) {
                                SaudiaBridge.onLog('CLICKED: '+t);
                                btns[i].click();
                                return;
                            }
                        }
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
                    SaudiaBridge.onLog('GAVE UP');
                }
            }, 500);
        })();
    """.trimIndent()
    view.evaluateJavascript(js, null)
}
