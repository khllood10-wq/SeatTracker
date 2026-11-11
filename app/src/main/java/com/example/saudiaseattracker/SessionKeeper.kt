package com.example.saudiaseattracker

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import org.json.JSONObject

@SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
@Composable
fun SessionKeeper(
    viewModel: FlightViewModel,
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
    var captured by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("ابحث عن رحلة واخترها — التوكن يُلتقط تلقائياً") }
    var showWebView by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        delay(300_000) // 5 دقائق
        if (!captured) {
            statusText = "⏱️ انتهت المهلة — أغلق وأعد المحاولة"
            onComplete()
        }
    }

    val bridge = remember {
        object {
            @JavascriptInterface
            fun onJwtFound(token: String) {
                if (captured) return
                val cleanToken = token.removePrefix("Bearer ").trim()
                android.util.Log.e("SessionKeeper", "🎯 JWT FOUND! len=${cleanToken.length}")

                var exp = 0L
                var iat = 0L
                try {
                    val parts = cleanToken.split(".")
                    if (parts.size >= 2) {
                        val part = parts[1]
                        val padded = part + "=".repeat((4 - part.length % 4) % 4)
                        val payload = String(Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP))
                        val json = JSONObject(payload)
                        exp = json.optLong("exp", 0)
                        iat = json.optLong("iat", 0)
                        val remaining = exp - System.currentTimeMillis() / 1000
                        android.util.Log.e("SessionKeeper", "JWT exp=$exp remaining=${remaining}s")
                        if (exp > 0 && remaining < 300) return
                    }
                } catch (e: Exception) {
                    android.util.Log.e("SessionKeeper", "JWT decode fail: ${e.message}")
                    return
                }

                captured = true
                statusText = "✅ تم التقاط التوكن!"
                viewModel.saveTokens(token, "", "", "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")

                try {
                    val cm = CookieManager.getInstance()
                    val sau = cm.getCookie("https://www.saudia.com") ?: ""
                    val dapi = cm.getCookie("https://dapi.saudia.com") ?: ""
                    val merged = listOf(sau, dapi).filter { it.isNotBlank() }
                        .joinToString("; ").replace(Regex(";+"), ";")
                        .trim().trimStart(';', ' ')
                    if (merged.isNotBlank()) {
                        prefs.edit().putString("dynamic_cookies", merged).apply()
                        android.util.Log.e("SessionKeeper", "🍪 Cookies: ${merged.length}ch")
                    }
                } catch (e: Exception) {}

                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    onComplete()
                }, 1500)
            }

            @JavascriptInterface
            fun onLog(msg: String) {
                android.util.Log.e("SessionKeeper", "📝 JS: $msg")
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (captured) Color(0xFF00C853) else Color(0xFFFFA726)
            )
        ) {
            Row(
                Modifier.padding(12.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!captured) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    statusText,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        }

        if (showWebView) {
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.javaScriptCanOpenWindowsAutomatically = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        settings.userAgentString =
                            "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

                        addJavascriptInterface(bridge, "SaudiaBridge")

                        webChromeClient = object : WebChromeClient() {}
                        val cm = CookieManager.getInstance()
                        cm.setAcceptCookie(true)
                        cm.setAcceptThirdPartyCookies(this, true)

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                android.util.Log.e("SessionKeeper", "🌐 PAGE: $url")
                                view?.evaluateJavascript(SESSION_HOOK_JS, null)
                            }
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                android.util.Log.e("SessionKeeper", "✅ LOADED: $url")
                            }
                        }

                        loadUrl("https://www.saudia.com/ar-SA")
                    }
                }
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(Modifier.padding(10.dp)) {
                Text("📋 التعليمات (بدون تسجيل دخول):", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    "1. ابحث عن رحلة (مثلاً JED → AHB)\n" +
                    "2. اختر أي رحلة متاحة\n" +
                    "3. ستنتقل لصفحة المسافرين\n" +
                    "4. عند فتح صفحة المسافرين → التوكن يُلتقط تلقائياً",
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        captured = true
                        onComplete()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("إلغاء", fontSize = 12.sp)
                }
            }
        }
    }
}

private val SESSION_HOOK_JS = """
    (function() {
        if (window.__sk) return;
        window.__sk = true;

        function getH(h, n) {
            if (!h) return '';
            if (typeof h.get === 'function') return h.get(n) || h.get(n.toLowerCase()) || '';
            for (var k in h) if (k.toLowerCase() === n.toLowerCase()) return h[k] || '';
            return '';
        }

        var origFetch = window.fetch;
        window.fetch = function(input, init) {
            var url = typeof input === 'string' ? input : (input && input.url) || '';
            var isTok = url.indexOf('/session/auth/web/token/initialization') > -1;
            if (isTok) SaudiaBridge.onLog('fetch → token/init');
            var p = orig.apply(this, arguments);
            if (isTok) {
                p.then(function(r) {
                    SaudiaBridge.onLog('fetch ← HTTP ' + r.status);
                    try {
                        r.clone().text().then(function(t) {
                            try {
                                var d = JSON.parse(t);
                                if (d.access_token) SaudiaBridge.onJwtFound('Bearer ' + d.access_token);
                            } catch(e) {}
                        });
                    } catch(e) {}
                });
            }
            return p;
        };

        var oO = XMLHttpRequest.prototype.open;
        var oS = XMLHttpRequest.prototype.send;
        var oH = XMLHttpRequest.prototype.setRequestHeader;
        XMLHttpRequest.prototype.open = function(m, u) {
            this.__u = u; this.__h = {};
            return oO.apply(this, arguments);
        };
        XMLHttpRequest.prototype.setRequestHeader = function(k, v) {
            if (this.__h) this.__h[k] = v;
            return oH.apply(this, arguments);
        };
        XMLHttpRequest.prototype.send = function() {
            var self = this;
            var isTok = self.__u && self.__u.indexOf('/session/auth/web/token/initialization') > -1;
            if (isTok) SaudiaBridge.onLog('XHR → token/init');
            if (isTok) {
                self.addEventListener('load', function() {
                    SaudiaBridge.onLog('XHR ← HTTP ' + self.status);
                    try {
                        var d = JSON.parse(self.responseText);
                        if (d.access_token) SaudiaBridge.onJwtFound('Bearer ' + d.access_token);
                    } catch(e) {}
                });
            }
            return oS.apply(this, arguments);
        };

        // Polling كل 5 ثوانٍ — يبحث عن توكن محفوظ
        setInterval(function() {
            try {
                var candidates = [
                    sessionStorage.getItem('saudia-access-token'),
                    localStorage.getItem('saudia-access-token'),
                    localStorage.getItem('authorization'),
                    localStorage.getItem('token')
                ];
                for (var i = 0; i < candidates.length; i++) {
                    var t = candidates[i];
                    if (t && t.indexOf('eyJ') === 0) {
                        SaudiaBridge.onJwtFound('Bearer ' + t);
                        return;
                    }
                    if (t && t.indexOf('Bearer ') === 0 && t.indexOf('eyJ') > -1) {
                        SaudiaBridge.onJwtFound(t);
                        return;
                    }
                }
            } catch(e) {}
        }, 5000);

        SaudiaBridge.onLog('Hooks installed');
    })();
""".trimIndent()
