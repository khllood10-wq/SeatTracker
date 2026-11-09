package com.example.saudiaseattracker

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun KeyCaptureScreen(navController: NavController) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
    val cartId = prefs.getString("cart_id_for_capture", null)
    val sessionId = prefs.getString("session_id", "") ?: ""
    val xDToken = prefs.getString("xd_token", "") ?: ""
    val authToken = prefs.getString("auth_token", "") ?: ""

    var captured by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("جاري تحميل الصفحة...") }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    // إن لم يوجد cartId → خطأ
    if (cartId.isNullOrEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("❌ لا يوجد cartId. ارجع لصفحة المسافرين أولاً.")
                Spacer(Modifier.height(16.dp))
                Button(onClick = { navController.popBackStack() }) { Text("رجوع") }
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("التقاط المفتاح (مرة واحدة)", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(statusText, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Text("cartId: $cartId", style = MaterialTheme.typography.bodySmall)
            }
        }

        if (!captured) {
            Button(
                onClick = {
                    statusText = "جاري التقاط المفتاح..."
                    webViewRef?.evaluateJavascript("""
                        (function() {
                            var setVal = function(name, val) {
                                var el = document.querySelector('input[formcontrolname="' + name + '"]');
                                if (!el) return false;
                                var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
                                setter.call(el, val);
                                el.dispatchEvent(new Event('input', {bubbles:true}));
                                el.dispatchEvent(new Event('change', {bubbles:true}));
                                return true;
                            };
                            setVal('firstName', 'TEST');
                            setVal('lastName', 'TEST');
                            setVal('dateOfBirth', '1990-01-01');
                            setVal('documentNumber', '1234567890');
                            setTimeout(function() {
                                var btns = document.querySelectorAll('button');
                                for (var i = 0; i < btns.length; i++) {
                                    if ((btns[i].innerText || '').indexOf('تأكيد') > -1) {
                                        btns[i].click();
                                        break;
                                    }
                                }
                            }, 800);
                        })();
                    """.trimIndent(), null)
                },
                modifier = Modifier.fillMaxWidth().padding(8.dp)
            ) {
                Text("التقط المفتاح الآن (اضغط مرة واحدة)")
            }
        }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    webViewRef = this
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

                    val cm = CookieManager.getInstance()
                    cm.setAcceptCookie(true)
                    cm.setAcceptThirdPartyCookies(this, true)

                    // حقن الكوكيز المحفوظة
                    val cookies = prefs.getString("dynamic_cookies", "") ?: ""
                    if (cookies.isNotEmpty()) {
                        listOf("https://www.saudia.com", "https://dapi.saudia.com").forEach {
                            cm.setCookie(it, cookies)
                        }
                        cm.flush()
                    }

                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun onPublicKey(pem: String) {
                            if (!pem.contains("MII") && !pem.contains("BEGIN")) return
                            if (captured) return
                            prefs.edit().putString("rsa_public_key", pem).apply()
                            captured = true
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                navController.popBackStack()
                            }
                        }
                    }, "AndroidBridge")

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            // تثبيت الـ hooks قبل تحميل الصفحة
                            view?.evaluateJavascript("""
                                (function() {
                                    if (window.__keyHookInstalled) return;
                                    window.__keyHookInstalled = true;

                                    // حقن التوكنات
                                    localStorage.setItem('saa-session-id', '$sessionId');
                                    localStorage.setItem('x-d-token', '$xDToken');
                                    localStorage.setItem('authorization', 'Bearer $authToken');

                                    // Hook 1: Web Crypto
                                    if (window.crypto && window.crypto.subtle) {
                                        var origImport = crypto.subtle.importKey;
                                        crypto.subtle.importKey = function(format, keyData, algorithm, extractable, keyUsages) {
                                            try {
                                                if (algorithm && (algorithm.name === 'RSA-OAEP' || algorithm.name === 'RSAES-PKCS1-v1_5')) {
                                                    var bytes = new Uint8Array(keyData);
                                                    var b64 = btoa(String.fromCharCode.apply(null, bytes));
                                                    var pem = '-----BEGIN PUBLIC KEY-----\n';
                                                    for (var i = 0; i < b64.length; i += 64) pem += b64.substring(i, i + 64) + '\n';
                                                    pem += '-----END PUBLIC KEY-----';
                                                    AndroidBridge.onPublicKey(pem);
                                                }
                                            } catch(e) {}
                                            return origImport.apply(this, arguments);
                                        };
                                    }

                                    // Hook 2: JSEncrypt
                                    setInterval(function() {
                                        if (window.JSEncrypt && window.JSEncrypt.prototype && !window.__jsencryptHooked) {
                                            window.__jsencryptHooked = true;
                                            var origSet = JSEncrypt.prototype.setPublicKey;
                                            JSEncrypt.prototype.setPublicKey = function(pem) {
                                                try { AndroidBridge.onPublicKey(pem); } catch(e) {}
                                                return origSet.apply(this, arguments);
                                            };
                                        }
                                    }, 200);

                                    // Hook 3: forge
                                    setInterval(function() {
                                        if (window.forge && window.forge.pki && !window.__forgeHooked) {
                                            window.__forgeHooked = true;
                                            var origFrom = forge.pki.publicKeyFromPem;
                                            forge.pki.publicKeyFromPem = function(pem) {
                                                try { AndroidBridge.onPublicKey(pem); } catch(e) {}
                                                return origFrom.apply(this, arguments);
                                            };
                                        }
                                    }, 200);
                                })();
                            """.trimIndent(), null)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            if (!captured) statusText = "الصفحة محملة. اضغط الزر أعلاه للتقاط المفتاح."
                        }
                    }

                    loadUrl("https://www.saudia.com/ar-SA/booking/passengerDetails?cartId=$cartId")
                }
            }
        )
    }
}
