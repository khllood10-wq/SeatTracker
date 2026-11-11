package com.example.saudiaseattracker

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
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

/**
 * شاشة تجهيز الجلسة — WebView أسود مرئي.
 * يظهر عند فتح التطبيق لأول مرة (أو عند تجديد JWT).
 * يختفي تلقائياً بمجرد التقاط JWT.
 */
@SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
@Composable
fun SessionKeeper(
    viewModel: FlightViewModel,
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
    var captured by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("جاري تحميل الجلسة...") }
    var showWebView by remember { mutableStateOf(false) }

    // ⏱️ بعد 30 ثانية إذا لم نلتقط، انتهت المحاولة
    LaunchedEffect(Unit) {
        delay(30_000)
        if (!captured) {
            android.util.Log.w("SessionKeeper", "Timeout — JWT not captured")
            onComplete()
        }
    }

    // ⏱️ أظهر WebView بعد 500ms (لتفادي تسلسل مشاكل التهيئة)
    LaunchedEffect(Unit) {
        delay(500)
        showWebView = true
        android.util.Log.d("SessionKeeper", "WebView now visible")
    }

    val bridge = remember {
        object {
            @JavascriptInterface
            fun onTokenCaptured(auth: String, xD: String, sid: String, ua: String) {
                if (captured) return
                captured = true
                android.util.Log.d("SessionKeeper", "JWT captured!")
                statusText = "✅ تم تجهيز الجلسة"

                // حفظ
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
                    android.util.Log.e("SessionKeeper", "Cookie: ${e.message}")
                }

                // ⏱️ انتظر 300ms ثم أغلق (ليرى المستخدم "✅")
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    onComplete()
                }, 300)
            }

            @JavascriptInterface
            fun onXDTokenCaptured(xD: String) {
                prefs.edit().putString("xd_token", xD).apply()
                android.util.Log.d("SessionKeeper", "x-d-token captured")
            }

            @JavascriptInterface
            fun onLog(msg: String) {
                android.util.Log.d("SessionKeeper", msg)
            }
        }
    }

    // ⭐ شاشة سوداء كاملة مرئية
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // WebView (سيظهر بعد 500ms)
        if (showWebView) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    android.util.Log.d("SessionKeeper", "WebView created")
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
                                android.util.Log.d("SessionKeeper", "Page loading: $url")
                                view?.evaluateJavascript(SESSION_HOOK_JS, null)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                android.util.Log.d("SessionKeeper", "Page finished: $url")
                                view?.evaluateJavascript("""
                                    setTimeout(function() {
                                        try {
                                            var auth = localStorage.getItem('authorization') ||
                                                       localStorage.getItem('token') ||
                                                       sessionStorage.getItem('saudia-access-token');
                                            var xD = localStorage.getItem('x-d-token') || '';
                                            var sid = localStorage.getItem('saa-session-id') || '';
                                            if (auth && auth.indexOf('Bearer') > -1) {
                                                SaudiaBridge.onTokenCaptured(auth, xD, sid, navigator.userAgent);
                                            }
                                        } catch(e) {}
                                    }, 3000);
                                """.trimIndent(), null)
                            }
                        }

                        loadUrl("https://www.saudia.com/ar-SA")
                    }
                }
            )
        }

        // ⭐ الطبقة السوداء + الحالة (فوق WebView، شفافة جزئياً)
        // تجعل الشاشة سوداء مع مؤشر تحميل مرئي
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(
                    modifier = Modifier.size(64.dp),
                    color = Color(0xFF00C853),
                    strokeWidth = 4.dp
                )
                Spacer(Modifier.height(24.dp))
                Text(
                    "✈️ تجهيز الجلسة",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    statusText,
                    color = Color(0xFFAAAAAA),
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "يعمل في الخلفية — لن يستغرق سوى ثوانٍ",
                    color = Color(0xFF666666),
                    fontSize = 11.sp
                )
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
        var orig = window.fetch;
        window.fetch = function(input, init) {
            var url = typeof input === 'string' ? input : (input && input.url) || '';
            var isTok = url.indexOf('/session/auth/web/token/initialization') > -1;
            if (isTok && init && init.headers) {
                try {
                    var xd = getH(init.headers, 'x-d-token');
                    if (xd) SaudiaBridge.onXDTokenCaptured(xd);
                } catch(e) {}
            }
            var p = orig.apply(this, arguments);
            if (isTok) {
                p.then(function(r) {
                    try {
                        r.clone().text().then(function(t) {
                            try {
                                var d = JSON.parse(t);
                                if (d.access_token) {
                                    SaudiaBridge.onTokenCaptured(
                                        'Bearer ' + d.access_token, '',
                                        d.session_id || '', navigator.userAgent
                                    );
                                }
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
            if (self.__u && self.__u.indexOf('/session/auth/web/token/initialization') > -1) {
                if (self.__h) {
                    for (var k in self.__h) {
                        if (k.toLowerCase() === 'x-d-token') {
                            SaudiaBridge.onXDTokenCaptured(self.__h[k]);
                        }
                    }
                }
                self.addEventListener('load', function() {
                    try {
                        var d = JSON.parse(self.responseText);
                        if (d.access_token) {
                            SaudiaBridge.onTokenCaptured(
                                'Bearer ' + d.access_token, '',
                                d.session_id || '', navigator.userAgent
                            );
                        }
                    } catch(e) {}
                });
            }
            return oS.apply(this, arguments);
        };
    })();
""".trimIndent()
