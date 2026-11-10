package com.example.saudiaseattracker

import android.annotation.SuppressLint
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
@Composable
fun AutoConfirmScreen(navController: NavController, viewModel: FlightViewModel) {
    val context = LocalContext.current
    val foundFlight = viewModel.foundFlight.collectAsStateWithLifecycle().value
    val prefs = context.getSharedPreferences("auth_prefs", android.content.Context.MODE_PRIVATE)
    val sessionId = prefs.getString("session_id", "") ?: ""
    val xDToken = prefs.getString("xd_token", "") ?: ""
    val authToken = prefs.getString("auth_token", "") ?: ""
    val airBoundId = foundFlight?.airBoundId ?: ""

    var status by remember { mutableStateOf("⏳ جاري التجهيز (الرجاء الانتظار)...") }
    var isDone by remember { mutableStateOf(false) }
    var webView: WebView? = null

    val bridge = remember {
        object {
            @JavascriptInterface
            fun onCartCreated(cartId: String) {
                Handler(Looper.getMainLooper()).post {
                    viewModel.updateCartId(cartId)
                    status = "✅ تم إنشاء السلة. جاري الانتقال لصفحة المسافرين..."
                    // الانتقال لصفحة المسافرين في نفس المتصفح
                    webView?.loadUrl("https://www.saudia.com/ar-SA/booking/passengerDetails?cartId=$cartId")
                }
            }

            @JavascriptInterface
            fun onFillForm() {
                Handler(Looper.getMainLooper()).post {
                    status = "📝 جاري ملء بيانات المسافر والضغط على تأكيد..."
                }
            }

            @JavascriptInterface
            fun onSuccess(statusCode: Int) {
                Handler(Looper.getMainLooper()).post {
                    if (statusCode == 200) {
                        status = "🎉 تم تأكيد المسافر بنجاح (HTTP 200)!"
                        isDone = true
                    } else {
                        status = "❌ فشل: HTTP $statusCode"
                    }
                }
            }
        }
    }

    fun startProcess() {
        status = "🛒 جاري إنشاء السلة عبر المتصفح..."
        webView?.evaluateJavascript("""
            (function() {
                var xhr = new XMLHttpRequest();
                xhr.open("POST", "https://dapi.saudia.com/shopping/carts?guestOfficeId=JEDSV08MA&lang=ar-SA", true);
                xhr.setRequestHeader("Content-Type", "application/json");
                xhr.setRequestHeader("Authorization", "$authToken");
                xhr.setRequestHeader("x-d-token", "$xDToken");
                xhr.setRequestHeader("saa-session-id", "$sessionId");
                xhr.setRequestHeader("x-api-version", "2");
                xhr.setRequestHeader("platform", "Web");
                xhr.setRequestHeader("version", "27.2");
                xhr.setRequestHeader("channel", "MOBILE_WEB");
                
                xhr.onreadystatechange = function() {
                    if (xhr.readyState === 4 && (xhr.status === 200 || xhr.status === 201)) {
                        try {
                            var data = JSON.parse(xhr.responseText);
                            var cartId = data.id || (data.data && data.data.id);
                            if (cartId) SaudiaBridge.onCartCreated(cartId);
                        } catch(e) {}
                    }
                };
                xhr.send(JSON.stringify({ airBoundIds: ["$airBoundId"] }));
            })();
        """.trimIndent(), null)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (!isDone) {
            CircularProgressIndicator(modifier = Modifier.size(64.dp), color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(24.dp))
            Text(status, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            Text("لا تغلق التطبيق. هذه العملية تتم في الخلفية.", style = MaterialTheme.typography.bodySmall)
        } else {
            Text(status, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(32.dp))
            Button(
                onClick = {
                    navController.navigate(Screen.Payment.route) {
                        popUpTo(Screen.Passengers.route) { inclusive = true }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(60.dp)
            ) {
                Text("➡️ الانتقال لصفحة الدفع", fontSize = 18.sp)
            }
        }

        // الـ WebView مخفي (1 بكسل) ليقوم بالعمل في الخلفية
        androidx.compose.ui.viewinterop.AndroidView(
            modifier = Modifier.size(1.dp),
            factory = { ctx ->
                WebView(ctx).apply {
                    webView = this
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = "Mozilla/5.0 (Linux; Android 14; SM-S918B) " +
                            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                    addJavascriptInterface(bridge, "SaudiaBridge")

                    val cm = CookieManager.getInstance()
                    cm.setAcceptCookie(true)
                    cm.setAcceptThirdPartyCookies(this, true)

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            // حقن السكريبت لاعتراض الـ PATCH
                            view?.evaluateJavascript("""
                                (function() {
                                    if (window.__hooked) return;
                                    window.__hooked = true;
                                    var origFetch = window.fetch;
                                    window.fetch = function(input, init) {
                                        var url = typeof input === 'string' ? input : (input && input.url);
                                        var method = ((init && init.method) || 'GET').toUpperCase();
                                        if (url && url.indexOf('/travelers/web') > -1 && method === 'PATCH') {
                                            var promise = origFetch.apply(this, arguments);
                                            promise.then(function(response) {
                                                SaudiaBridge.onSuccess(response.status);
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
                            if (url == null) return
                            
                            // بعد تحميل صفحة المسافرين، نقوم بملء الفورم والضغط
                            if (url.contains("passengerDetails") && !isDone) {
                                SaudiaBridge.onFillForm()
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
                                                    }, 1500);
                                                }
                                                if (attempts > 20) clearInterval(iv);
                                            }, 500);
                                        })();
                                    """.trimIndent(), null)
                                }, 3000)
                            }
                        }
                    }
                    loadUrl("https://www.saudia.com/ar-SA")
                }
            }
        )
    }

    // بدء العملية فوراً عند فتح الشاشة
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(2000) // انتظار تحميل الصفحة الرئيسية
        startProcess()
    }
}
