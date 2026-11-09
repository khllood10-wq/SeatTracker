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

    var captured by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("جاري تحميل الصفحة والتقاط المفتاح...") }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    // تحقق مبدئي: هل المفتاح موجود أصلاً؟
    LaunchedEffect(Unit) {
        val existing = prefs.getString("rsa_public_key", null)
        if (!existing.isNullOrEmpty()) {
            captured = true
            navController.navigate(Screen.Login.route) {
                popUpTo(Screen.KeyCapture.route) { inclusive = true }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {

        // شريط الحالة العلوي
        Card(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("التقاط مفتاح التشفير (مرة واحدة فقط)", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(statusText, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    "بعد نجاح الالتقاط، سيُغلق المتصفح نهائياً ولن يفتح مرة أخرى.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        // زر احتياطي — فقط إذا لم ينجح الالتقاط التلقائي
        if (!captured) {
            Button(
                onClick = {
                    statusText = "جاري محاولة الالتقاط اليدوي..."
                    // حقن JS يملأ بيانات وهمية ويضغط زر المتابعة
                    // هذا مجرد "تشغيل" للكود التقني، أنت لا تملأ أي شيء
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
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
            ) {
                Text("لم ينجح تلقائياً — اضغط للتفعيل التقني (بدون تعبئة منك)")
            }
            Spacer(Modifier.height(4.dp))
        }

        // WebView
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    webViewRef = this
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun onPublicKey(pem: String) {
                            if (!pem.contains("MII") && !pem.contains("BEGIN")) return
                            if (captured) return

                            prefs.edit().putString("rsa_public_key", pem).apply()
                            captured = true
                            statusText = "✅ تم التقاط المفتاح (${pem.length} حرف)"

                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                navController.navigate(Screen.Login.route) {
                                    popUpTo(Screen.KeyCapture.route) { inclusive = true }
                                }
                            }
                        }
                    }, "AndroidBridge")

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            super.onPageStarted(view, url, favicon)

                            // ✅ تثبيت الخطافات قبل تحميل الصفحة (حرج)
                            view?.evaluateJavascript("""
                                (function() {
                                    if (window.__keyHookInstalled) return;
                                    window.__keyHookInstalled = true;

                                    // Hook 1: Web Crypto API
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
                                    var chk1 = setInterval(function() {
                                        if (window.JSEncrypt && window.JSEncrypt.prototype) {
                                            clearInterval(chk1);
                                            var origSet = JSEncrypt.prototype.setPublicKey;
                                            JSEncrypt.prototype.setPublicKey = function(pem) {
                                                try { AndroidBridge.onPublicKey(pem); } catch(e) {}
                                                return origSet.apply(this, arguments);
                                            };
                                        }
                                    }, 200);

                                    // Hook 3: forge.pki
                                    var chk2 = setInterval(function() {
                                        if (window.forge && window.forge.pki) {
                                            clearInterval(chk2);
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
                            if (!captured) statusText = "الصفحة محملة. إن لم يُلتقط المفتاح تلقائياً، استخدم الزر أعلاه."
                        }
                    }

                    // جرّب صفحة السلة أولاً (الأخف)
                    loadUrl("https://www.saudia.com/ar-SA/booking")
                }
            }
        )
    }
}
