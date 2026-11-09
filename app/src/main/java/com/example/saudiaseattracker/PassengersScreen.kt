package com.example.saudiaseattracker

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import java.io.ByteArrayInputStream

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassengersScreen(
    navController: NavController,
    viewModel: FlightViewModel
) {
    val cartId = viewModel.cartId.collectAsStateWithLifecycle().value
    val rawCartResponse = viewModel.rawCartResponse.collectAsStateWithLifecycle().value.orEmpty()
    val context = LocalContext.current

    var capturedBody by remember { mutableStateOf<String?>(null) }
    val logs = viewModel.searchLogs.collectAsStateWithLifecycle().value
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    val prefs = context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
    val sessionId = prefs.getString("session_id", "") ?: ""
    val xDToken = prefs.getString("xd_token", "") ?: ""
    val authToken = prefs.getString("auth_token", "") ?: ""
    val dynamicCookies = prefs.getString("dynamic_cookies", "") ?: ""
    
    // التوكن السري من ملف الـ HAR
    val xVToken = "NjFkOGRhYzE0YWU4MDE4NDZhYmU0MzJiZGQ4YWZmYWE4ZTIyODdhMmVhMGVkZjY3ZTFkODc5MjA0NjFkYzdmODIxMTRiNTNiM2RhYTc2NTRiMzQ5Yzc4NDdlYjg1OTk0MTU2OGY3MTdiMWYwZjU3YjQyYWY2ZGYzMGMyZTA5NWQ="

    LaunchedEffect(Unit) {
        if (viewModel.cartId.value.isNullOrEmpty()) {
            viewModel.createCartAndProceedToPassenger()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("حالة الحجز التلقائي", style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { 
                        webViewRef?.loadUrl("https://www.saudia.com/ar-SA/booking/passengerDetails?cartId=$cartId")
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "تحديث")
                    }
                }
                Text("cartId: ${cartId ?: "..."}", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                logs.take(5).forEach { log ->
                    Text(log, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                }
                if (capturedBody != null) {
                    Button(
                        onClick = {
                            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cb.setPrimaryClip(ClipData.newPlainText("Captured", capturedBody))
                            Toast.makeText(context, "تم النسخ!", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                    ) { Text("نسخ الطلب المشفر") }
                }
            }
        }

        if (cartId.isNullOrEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { ctx ->
                    WebView(ctx).apply {
                        webViewRef = this
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

                        val cookieManager = CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)
                        
                        if (dynamicCookies.isNotEmpty()) {
                            val domains = listOf("https://www.saudia.com", "https://dapi.saudia.com")
                            domains.forEach { domain -> cookieManager.setCookie(domain, dynamicCookies) }
                            cookieManager.flush()
                        }

                        addJavascriptInterface(object {
                            @JavascriptInterface
                            fun onCaptured(url: String, body: String) {
                                viewModel.addLog("🎯 URL: $url")
                                capturedBody = "$url\n\n$body"
                            }
                            @JavascriptInterface
                            fun onLog(msg: String) { viewModel.addLog(msg) }
                        }, "AndroidBridge")

                        webViewClient = object : WebViewClient() {
                            
                            // ═══ السحر الحقيقي هنا: اعتراض طلب جلب السلة وإرجاع بياناتنا الجاهزة ═══
                            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                                val url = request?.url.toString()
                                
                                if (url.contains("/shopping/carts/") && url.contains("pageIdentity=PassengerDetails") && !url.contains("/services")) {
                                    if (rawCartResponse.isNotEmpty()) {
                                        viewModel.addLog("💉 تم اعتراض طلب السلة وتزويد المتصفح بالبيانات")
                                        return WebResourceResponse(
                                            "application/json",
                                            "UTF-8",
                                            ByteArrayInputStream(rawCartResponse.toByteArray())
                                        )
                                    }
                                }
                                return super.shouldInterceptRequest(view, request)
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                view?.evaluateJavascript("""
                                    localStorage.setItem('saa-session-id', '${sessionId}');
                                    localStorage.setItem('x-d-token', '${xDToken}');
                                    localStorage.setItem('authorization', 'Bearer ${authToken}');
                                    localStorage.setItem('x-v-token', '${xVToken}');
                                """.trimIndent(), null)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                viewModel.addLog("📄 Loaded: ${url?.take(40)}...")
                                
                                view?.evaluateJavascript("""
                                    (function() {
                                        if (window.__installed) return;
                                        window.__installed = true;

                                        var origFetch = window.fetch;
                                        window.fetch = function(u, o) {
                                            if (u && String(u).indexOf('dapi.saudia.com') > -1) {
                                                o = o || {};
                                                o.headers = o.headers || {};
                                                o.headers['saa-session-id'] = '${sessionId}';
                                                o.headers['x-d-token'] = '${xDToken}';
                                                o.headers['Authorization'] = 'Bearer ${authToken}';
                                                o.headers['browserid'] = 'db9387ab-b6f2-4d1a-9a08-0a55ac7a89d9';
                                                o.headers['channel'] = 'MOBILE_WEB';
                                                o.headers['x-api-version'] = '3';
                                                o.headers['x-v-token'] = '${xVToken}';
                                            }
                                            try {
                                                if (o && o.body && u && String(u).indexOf('travelers/web') > -1) {
                                                    AndroidBridge.onCaptured(String(u), String(o.body));
                                                }
                                            } catch(e) {}
                                            return origFetch.apply(this, arguments);
                                        };

                                        var origOpen = XMLHttpRequest.prototype.open;
                                        XMLHttpRequest.prototype.open = function() {
                                            this.addEventListener('readystatechange', function() {
                                                if (this.readyState === 1 && this.responseURL && this.responseURL.indexOf('dapi.saudia.com') > -1) {
                                                    try {
                                                        this.setRequestHeader('saa-session-id', '${sessionId}');
                                                        this.setRequestHeader('x-d-token', '${xDToken}');
                                                        this.setRequestHeader('Authorization', 'Bearer ${authToken}');
                                                        this.setRequestHeader('browserid', 'db9387ab-b6f2-4d1a-9a08-0a55ac7a89d9');
                                                        this.setRequestHeader('channel', 'MOBILE_WEB');
                                                        this.setRequestHeader('x-api-version', '3');
                                                        this.setRequestHeader('x-v-token', '${xVToken}');
                                                    } catch(e){}
                                                }
                                            }, false);
                                            origOpen.apply(this, arguments);
                                        };

                                        var ox = XMLHttpRequest.prototype.send;
                                        XMLHttpRequest.prototype.send = function(b) {
                                            try {
                                                if (b && this.__u__ && this.__u__.indexOf('travelers/web') > -1) {
                                                    AndroidBridge.onCaptured(String(this.__u__), String(b));
                                                }
                                            } catch(e) {}
                                            return ox.apply(this, arguments);
                                        };
                                        
                                        var oo = XMLHttpRequest.prototype.open;
                                        XMLHttpRequest.prototype.open = function(m, u) {
                                            this.__u__ = String(u);
                                            return oo.apply(this, arguments);
                                        };

                                        AndroidBridge.onLog("✅ JS Ready. Waiting for form...");

                                        function setInput(name, val) {
                                            var el = document.querySelector('input[formcontrolname="' + name + '"]');
                                            if (!el) return false;
                                            var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
                                            setter.call(el, val);
                                            el.dispatchEvent(new Event('input', {bubbles:true}));
                                            el.dispatchEvent(new Event('change', {bubbles:true}));
                                            return true;
                                        }

                                        var attempts = 0;
                                        var loop = setInterval(function() {
                                            attempts++;
                                            if (setInput('firstName', 'Khaled') && setInput('lastName', 'Otudi')) {
                                                clearInterval(loop);
                                                AndroidBridge.onLog("✅ Form found and filled!");
                                                setTimeout(function() {
                                                    setInput('dateOfBirth', '1991-05-10');
                                                    setInput('documentNumber', '1071580342');
                                                    setInput('email', 'Khllood10@gmail.com');
                                                    setInput('phoneNumber', '572260016');
                                                    
                                                    var btns = document.querySelectorAll('button');
                                                    for (var i = 0; i < btns.length; i++) {
                                                        var t = (btns[i].innerText || '').trim();
                                                        if (t.indexOf('تأكيد') > -1 || t.indexOf('Confirm') > -1) {
                                                            btns[i].click();
                                                            AndroidBridge.onLog("👉 Confirm clicked");
                                                            break;
                                                        }
                                                    }
                                                }, 1000);
                                            } else if (attempts > 30) {
                                                clearInterval(loop);
                                                AndroidBridge.onLog("⚠️ Timeout: Form never appeared.");
                                            }
                                        }, 500);
                                    })();
                                """.trimIndent(), null)
                            }
                        }

                        loadUrl("https://www.saudia.com/ar-SA/booking/passengerDetails?cartId=$cartId")
                    }
                }
            )
        }
    }
}
