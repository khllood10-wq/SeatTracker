package com.example.saudiaseattracker

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
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
import kotlinx.coroutines.isActive
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
    var statusText by remember { mutableStateOf("جاري تحميل الجلسة...") }
    var showWebView by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var pollCount by remember { mutableIntStateOf(0) }
    var pageFinished by remember { mutableStateOf(false) }
    var staleSkipped by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        delay(300)
        showWebView = true
    }

    LaunchedEffect(Unit) {
        delay(40_000)
        if (!captured) {
            android.util.Log.w("SessionKeeper", "Timeout — no fresh token")
            statusText = "⚠️ لم نلتقط توكن صالح"
            onComplete()
        }
    }

    LaunchedEffect(pageFinished, captured) {
        if (!pageFinished || captured) return@LaunchedEffect

        while (isActive && !captured) {
            pollCount++
            statusText = if (staleSkipped > 0)
                "🔍 فحص (${pollCount}): تجاهلنا $staleSkipped منتهي..."
            else "🔍 فحص التخزين... ($pollCount)"

            webViewRef?.evaluateJavascript("""
                (function() {
                    try {
                        var candidates = [
                            sessionStorage.getItem('saudia-access-token'),
                            localStorage.getItem('saudia-access-token'),
                            localStorage.getItem('authorization'),
                            localStorage.getItem('token'),
                            localStorage.getItem('access_token'),
                            sessionStorage.getItem('authorization'),
                            sessionStorage.getItem('token')
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
                        SaudiaBridge.onPollResult({
                            readyState: document.readyState,
                            hasSessionToken: !!sessionStorage.getItem('saudia-access-token'),
                            hasLocalAuth: !!localStorage.getItem('authorization')
                        });
                    } catch(e) {
                        SaudiaBridge.onPollError(e.message);
                    }
                })();
            """.trimIndent(), null)

            if (pollCount in listOf(3, 6, 10, 15, 20, 25, 30)) {
                android.util.Log.d("SessionKeeper", "Trigger click #$pollCount")
                webViewRef?.evaluateJavascript("""
                    (function() {
                        try {
                            var els = document.querySelectorAll('button, a, [role="button"]');
                            for (var i = 0; i < els.length; i++) {
                                if (els[i].offsetParent !== null && els[i].innerText) {
                                    els[i].click();
                                    SaudiaBridge.onLog('Clicked: ' + els[i].innerText.substring(0, 30));
                                    return;
                                }
                            }
                        } catch(e) {}
                    })();
                """.trimIndent(), null)
            }

            delay(500)
        }
    }

    val bridge = remember {
        object {
            @JavascriptInterface
            fun onJwtFound(token: String) {
                if (captured) return

                val cleanToken = token.removePrefix("Bearer ").trim()

                var exp = 0L
                var iat = 0L
                try {
                    val parts = cleanToken.split(".")
                    if (parts.size >= 2) {
                        val part = parts[1]
                        val padded = part + "=".repeat((4 - part.length % 4) % 4)
                        val payload = String(
                            Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP)
                        )
                        val json = JSONObject(payload)
                        exp = json.optLong("exp", 0)
                        iat = json.optLong("iat", 0)
                        val now = System.currentTimeMillis() / 1000
                        val remaining = exp - now

                        android.util.Log.d("SessionKeeper", "JWT iat=$iat exp=$exp remaining=${remaining}s")

                        if (exp > 0 && remaining < 300) {
                            staleSkipped++
                            android.util.Log.w("SessionKeeper", "⏭️ Skip stale JWT (${remaining}s left)")
                            return
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("SessionKeeper", "JWT decode: ${e.message}")
                    return
                }

                captured = true
                android.util.Log.d("SessionKeeper", "✅ Fresh JWT accepted (iat=$iat, exp=$exp)")
                statusText = "✅ تم التقاط توكن صالح جديد"

                viewModel.saveTokens(token, "", "", "Mozilla/5.0 (Linux; Android 14; SM-S918B)")

                prefs.edit()
                    .putLong("jwt_iat", iat)
                    .putLong("jwt_exp", exp)
                    .apply()

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

                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    onComplete()
                }, 400)
            }

            @JavascriptInterface
            fun onPollResult(json: String) {
                android.util.Log.d("SessionKeeper", "Poll $pollCount: $json")
            }

            @JavascriptInterface
            fun onPollError(msg: String) {
                android.util.Log.e("SessionKeeper", "Poll err: $msg")
            }

            @JavascriptInterface
            fun onXDTokenCaptured(xD: String) {
                prefs.edit().putString("xd_token", xD).apply()
            }

            @JavascriptInterface
            fun onLog(msg: String) {
                android.util.Log.d("SessionKeeper", msg)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (showWebView) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        webViewRef = this
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

                                // ⭐ امسح كل التخزين لفرض جلب توكن جديد
                                view?.evaluateJavascript("""
                                    (function() {
                                        try {
                                            sessionStorage.clear();
                                            localStorage.clear();
                                            SaudiaBridge.onLog('Cleared all storage');
                                        } catch(e) {
                                            SaudiaBridge.onLog('Clear failed: ' + e.message);
                                        }
                                    })();
                                """.trimIndent(), null)

                                view?.evaluateJavascript(SESSION_HOOK_JS, null)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                android.util.Log.d("SessionKeeper", "Page finished: $url")
                                pageFinished = true
                                statusText = "🔄 الصفحة محملة — بدء الفحص..."
                            }
                        }

                        loadUrl("https://www.saudia.com/ar-SA")
                    }
                }
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.85f)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(
                    modifier = Modifier.size(64.dp),
                    color = Color(0xFF00C853),
                    strokeWidth = 4.dp
                )
                Spacer(Modifier.height(24.dp))
                Text("✈️ تجهيز الجلسة",
                    color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Text(statusText,
                    color = Color(0xFFAAAAAA), fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 24.dp))
                Spacer(Modifier.height(8.dp))
                Text("يعمل في الخلفية — لن يستغرق سوى ثوانٍ",
                    color = Color(0xFF666666), fontSize = 11.sp)
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
                                    SaudiaBridge.onJwtFound('Bearer ' + d.access_token);
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
                            SaudiaBridge.onJwtFound('Bearer ' + d.access_token);
                        }
                    } catch(e) {}
                });
            }
            return oS.apply(this, arguments);
        };
    })();
""".trimIndent()
