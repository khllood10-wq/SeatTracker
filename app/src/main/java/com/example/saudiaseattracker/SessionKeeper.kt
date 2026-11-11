package com.example.saudiaseattracker

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.webkit.ConsoleMessage
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
    var staleSkipped by remember { mutableIntStateOf(0) }
    var lastDiag by remember { mutableStateOf("") }
    var hooksInstalledPages by remember { mutableIntStateOf(0) }

    remember {
        android.util.Log.e("SessionKeeper", "🔥 COMPOSABLE RENDERED")
        true
    }

    LaunchedEffect(Unit) {
        delay(300)
        showWebView = true
        android.util.Log.e("SessionKeeper", "📱 WebView will show")
    }

    // ⏱️ Timeout 90 ثانية (كافٍ حتى مع إعادة تحميل متعددة)
    LaunchedEffect(Unit) {
        delay(90_000)
        if (!captured) {
            android.util.Log.e("SessionKeeper", "⏱️ TIMEOUT 90s — last: $lastDiag")
            statusText = "⚠️ لم نلتقط — $lastDiag"
            onComplete()
        }
    }

    // ⭐⭐⭐ Polling مستمر — لا يعتمد على pageFinished ⭐⭐⭐
    LaunchedEffect(showWebView, captured) {
        if (!showWebView || captured) return@LaunchedEffect
        android.util.Log.e("SessionKeeper", "🔄 POLLING STARTED (independent of page state)")

        while (isActive && !captured) {
            pollCount++
            statusText = if (staleSkipped > 0)
                "🔍 فحص ($pollCount): تجاهلنا $staleSkipped منتهي..."
            else "🔍 فحص ($pollCount)..."

            webViewRef?.evaluateJavascript("""
                (function() {
                    try {
                        var candidates = [
                            sessionStorage.getItem('saudia-access-token'),
                            localStorage.getItem('saudia-access-token'),
                            localStorage.getItem('authorization'),
                            localStorage.getItem('token'),
                            localStorage.getItem('access_token')
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
                        
                        var diag = {
                            rs: document.readyState || '?',
                            url: (location.href || '').substring(0, 60),
                            ngV: (document.querySelector('[ng-version]') || {getAttribute: function(){return 'none'}}).getAttribute('ng-version') || 'none',
                            hasAppRoot: !!document.querySelector('app-root,saudia-web-app'),
                            ssLen: sessionStorage.length,
                            lsLen: localStorage.length,
                            scripts: document.scripts.length,
                            bodyLen: document.body ? document.body.innerHTML.length : 0
                        };
                        SaudiaBridge.onPollResult(JSON.stringify(diag));
                    } catch(e) {
                        SaudiaBridge.onPollError(e.message);
                    }
                })();
            """.trimIndent(), null)

            delay(500)
        }
    }

    val bridge = remember {
        object {
            @JavascriptInterface
            fun onJwtFound(token: String) {
                if (captured) return
                val cleanToken = token.removePrefix("Bearer ").trim()
                android.util.Log.e("SessionKeeper", "🎯 JWT found! len=${cleanToken.length}")

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
                        if (exp > 0 && remaining < 300) {
                            staleSkipped++
                            android.util.Log.e("SessionKeeper", "⏭️ SKIP stale")
                            return
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("SessionKeeper", "JWT decode fail: ${e.message}")
                    return
                }

                captured = true
                statusText = "✅ تم التقاط توكن صالح"
                viewModel.saveTokens(token, "", "", "Mozilla/5.0 (Linux; Android 14)")

                try {
                    val cm = CookieManager.getInstance()
                    val sau = cm.getCookie("https://www.saudia.com") ?: ""
                    val dapi = cm.getCookie("https://dapi.saudia.com") ?: ""
                    val merged = listOf(sau, dapi).filter { it.isNotBlank() }
                        .joinToString("; ").replace(Regex(";+"), ";")
                        .trim().trimStart(';', ' ')
                    if (merged.isNotBlank()) {
                        prefs.edit().putString("dynamic_cookies", merged).apply()
                    }
                } catch (e: Exception) {}

                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    onComplete()
                }, 400)
            }

            @JavascriptInterface
            fun onPollResult(json: String) {
                lastDiag = json.take(150)
                // سجل كل 5 ثوانٍ فقط (لتجنب spam)
                if (pollCount % 10 == 1) {
                    android.util.Log.e("SessionKeeper", "📊 Poll $pollCount: $json")
                }
            }

            @JavascriptInterface
            fun onPollError(msg: String) {
                android.util.Log.e("SessionKeeper", "❌ Poll err: $msg")
            }

            @JavascriptInterface
            fun onXDTokenCaptured(xD: String) {
                prefs.edit().putString("xd_token", xD).apply()
                android.util.Log.e("SessionKeeper", "🎫 x-d-token: ${xD.length}ch")
            }

            @JavascriptInterface
            fun onLog(msg: String) {
                android.util.Log.e("SessionKeeper", "📝 JS: $msg")
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

                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                                msg?.let {
                                    val lvl = it.messageLevel()
                                    if (lvl >= ConsoleMessage.MessageLevel.WARNING) {
                                        android.util.Log.e("SessionKeeper",
                                            "📺 Console[${lvl}]: ${it.message().take(200)} @${it.sourceId()?.substringAfterLast('/') ?: ""}:${it.lineNumber()}")
                                    }
                                }
                                return true
                            }
                        }

                        val cm = CookieManager.getInstance()
                        cm.setAcceptCookie(true)
                        cm.setAcceptThirdPartyCookies(this, true)

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                android.util.Log.e("SessionKeeper", "🌐 PAGE STARTED: $url")

                                // حقن الهوك في كل صفحة (قد لا يكون موجوداً بعد إعادة التحميل)
                                view?.evaluateJavascript(SESSION_HOOK_JS, null)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                hooksInstalledPages++
                                android.util.Log.e("SessionKeeper", "✅ PAGE FINISHED (#$hooksInstalledPages): $url")
                                statusText = "🔄 جاهز — فحص مستمر..."
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: android.webkit.WebResourceRequest?,
                                error: android.webkit.WebResourceError?
                            ) {
                                super.onReceivedError(view, request, error)
                                android.util.Log.e("SessionKeeper", "❌ WEB ERR: ${error?.description}")
                            }
                        }

                        loadUrl("https://www.saudia.com/ar-SA")
                    }
                }
            )
        }

        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)),
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
            }
        }
    }
}

private val SESSION_HOOK_JS = """
    (function() {
        if (window.__sk) return;
        window.__sk = true;

        window.addEventListener('error', function(e) {
            try {
                SaudiaBridge.onLog('JS ERR: ' + (e.message || '?') + ' @' + (e.filename || '?').split('/').pop() + ':' + (e.lineno || 0));
            } catch(x) {}
        });

        function getH(h, n) {
            if (!h) return '';
            if (typeof h.get === 'function') return h.get(n) || h.get(n.toLowerCase()) || '';
            for (var k in h) if (k.toLowerCase() === n.toLowerCase()) return h[k] || '';
            return '';
        }

        var origFetch = window.fetch;
        window.fetch = function(input, init) {
            var url = typeof input === 'string' ? input : (input && input.url) || '';
            // سجل كل طلب dapi
            if (url.indexOf('dapi.saudia') > -1 || url.indexOf('/session/auth') > -1) {
                SaudiaBridge.onLog('fetch → ' + url.substring(0, 80));
            }
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
                    SaudiaBridge.onLog('fetch ← HTTP ' + r.status);
                    try {
                        r.clone().text().then(function(t) {
                            try {
                                var d = JSON.parse(t);
                                if (d.access_token) SaudiaBridge.onJwtFound('Bearer ' + d.access_token);
                                else SaudiaBridge.onLog('No access_token: ' + t.substring(0, 150));
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
            if (self.__u && (self.__u.indexOf('dapi.saudia') > -1 || self.__u.indexOf('/session/auth') > -1)) {
                SaudiaBridge.onLog('XHR → ' + self.__u.substring(0, 80));
            }
            if (self.__u && self.__u.indexOf('/session/auth/web/token/initialization') > -1) {
                if (self.__h) {
                    for (var k in self.__h) {
                        if (k.toLowerCase() === 'x-d-token') SaudiaBridge.onXDTokenCaptured(self.__h[k]);
                    }
                }
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

        SaudiaBridge.onLog('Hooks installed');
    })();
""".trimIndent()
