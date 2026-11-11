package com.example.saudiaseattracker

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
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
import kotlin.random.Random

@SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
@Composable
fun SessionKeeper(
    viewModel: FlightViewModel,
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
    var captured by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("جاري تحميل الصفحة...") }
    var showWebView by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var pollCount by remember { mutableIntStateOf(0) }
    var staleSkipped by remember { mutableIntStateOf(0) }
    var lastBodyLen by remember { mutableIntStateOf(0) }
    var angularDetected by remember { mutableStateOf(false) }

    // ⭐ الحالة الأساسية — لا يبدأ polling قبل pageFinished
    var pageFinished by remember { mutableStateOf(false) }

    remember {
        android.util.Log.e("SessionKeeper", "🔥 COMPOSABLE RENDERED")
        true
    }

    LaunchedEffect(Unit) {
        delay(400)
        showWebView = true
        android.util.Log.e("SessionKeeper", "📱 WebView will show")
    }

    // ⏱️ Timeout 150 ثانية — كافٍ
    LaunchedEffect(Unit) {
        delay(150_000)
        if (!captured) {
            android.util.Log.e("SessionKeeper", "⏱️ TIMEOUT 150s (pageFinished=$pageFinished bodyLen=$lastBodyLen)")
            statusText = "⚠️ لم نلتقط (bodyLen=$lastBodyLen)"
            onComplete()
        }
    }

    // ⭐⭐⭐ Polling يبدأ فقط بعد pageFinished ⭐⭐⭐
    LaunchedEffect(pageFinished, captured) {
        if (!pageFinished || captured) return@LaunchedEffect

        // ⏱️ انتظار أولي طبيعي — Angular يحتاج 8-15 ثانية
        val initialWait = Random.nextLong(8000, 15000)
        statusText = "⏳ انتظار Angular (${initialWait / 1000}s)..."
        android.util.Log.e("SessionKeeper", "⏳ PAGE FINISHED — waiting ${initialWait}ms for Angular")
        delay(initialWait)

        if (captured) return@LaunchedEffect

        android.util.Log.e("SessionKeeper", "🔍 SLOW POLLING STARTED (max 6 polls, 4-8s intervals)")

        while (isActive && !captured && pollCount < 6) {
            pollCount++

            val diagText = if (angularDetected) "✅ Angular يعمل" else "🔎 فحص"
            statusText = "$diagText (${pollCount}/6)"

            webViewRef?.evaluateJavascript("""
                (function() {
                    try {
                        var candidates = [];
                        try {
                            candidates.push(sessionStorage.getItem('saudia-access-token'));
                            candidates.push(sessionStorage.getItem('authorization'));
                            candidates.push(sessionStorage.getItem('token'));
                        } catch(e) {}
                        try {
                            candidates.push(localStorage.getItem('saudia-access-token'));
                            candidates.push(localStorage.getItem('authorization'));
                            candidates.push(localStorage.getItem('token'));
                            candidates.push(localStorage.getItem('access_token'));
                        } catch(e) {}

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

                        var ngV = 'none';
                        try {
                            var ngEl = document.querySelector('[ng-version]');
                            if (ngEl) ngV = ngEl.getAttribute('ng-version') || 'empty';
                        } catch(e) {}

                        var diag = {
                            rs: document.readyState || '?',
                            ngV: ngV,
                            appRoot: !!document.querySelector('app-root,saudia-web-app'),
                            bodyLen: document.body ? document.body.innerHTML.length : 0,
                            scripts: document.scripts.length,
                            hasWvMarker: document.body ? document.body.innerHTML.indexOf('incapsula') > -1 : false
                        };
                        SaudiaBridge.onPollResult(JSON.stringify(diag));
                    } catch(e) {
                        SaudiaBridge.onPollError(e.message);
                    }
                })();
            """.trimIndent(), null)

            // ⭐ فاصل عشوائي 4-8 ثوانٍ
            val nextWait = Random.nextLong(4000, 8001)
            delay(nextWait)
        }

        android.util.Log.e("SessionKeeper", "Polling finished: $pollCount polls, captured=$captured")
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
                        if (exp > 0 && remaining < 300) {
                            staleSkipped++
                            android.util.Log.e("SessionKeeper", "⏭️ SKIP stale")
                            return
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("SessionKeeper", "❌ JWT decode fail: ${e.message}")
                    return
                }

                captured = true
                statusText = "✅ تم التقاط توكن صالح"
                viewModel.saveTokens(token, "", "", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

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
                try {
                    val obj = JSONObject(json)
                    val bodyLen = obj.optInt("bodyLen", 0)
                    val ngV = obj.optString("ngV", "none")
                    val hasWvMarker = obj.optBoolean("hasWvMarker", false)
                    lastBodyLen = bodyLen

                    if (bodyLen > 10000 || (ngV != "none" && ngV != "empty")) {
                        angularDetected = true
                    }

                    android.util.Log.e("SessionKeeper", "📊 Poll $pollCount: $json")

                    if (hasWvMarker) {
                        android.util.Log.e("SessionKeeper", "⚠️ Incapsula in body — bot challenge")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("SessionKeeper", "❌ Parse poll: ${e.message}")
                }
            }

            @JavascriptInterface
            fun onPollError(msg: String) {
                if (!msg.contains("sessionStorage") && !msg.contains("localStorage")) {
                    android.util.Log.e("SessionKeeper", "❌ Poll err: $msg")
                }
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
                        settings.userAgentString =
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                            "AppleWebKit/537.36 (KHTML, like Gecko) " +
                            "Chrome/120.0.0.0 Safari/537.36"

                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.javaScriptCanOpenWindowsAutomatically = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.setSupportMultipleWindows(false)
                        settings.builtInZoomControls = false
                        settings.displayZoomControls = false
                        settings.allowFileAccess = false
                        settings.allowContentAccess = true
                        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT

                        WebView.setWebContentsDebuggingEnabled(false)

                        addJavascriptInterface(bridge, "SaudiaBridge")

                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                                msg?.let {
                                    if (it.messageLevel() >= ConsoleMessage.MessageLevel.ERROR) {
                                        android.util.Log.e("SessionKeeper",
                                            "📺 ${it.message().take(200)} @${it.sourceId()?.substringAfterLast('/') ?: ""}:${it.lineNumber()}")
                                    }
                                }
                                return true
                            }

                            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                                result?.confirm(); return true
                            }

                            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                                result?.confirm(); return true
                            }

                            override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?): Boolean {
                                result?.confirm(defaultValue ?: ""); return true
                            }
                        }

                        val cm = CookieManager.getInstance()
                        cm.setAcceptCookie(true)
                        cm.setAcceptThirdPartyCookies(this, true)

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                android.util.Log.e("SessionKeeper", "🌐 PAGE STARTED: $url")
                                view?.evaluateJavascript(ANTI_DETECT_JS, null)
                                view?.evaluateJavascript(SESSION_HOOK_JS, null)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                android.util.Log.e("SessionKeeper", "✅ PAGE FINISHED: $url")

                                // ⭐ تحقق أن الصفحة الحقيقية (وليس صفحة خطأ)
                                if (url != null && url.contains("saudia.com") && !url.contains("incident")) {
                                    pageFinished = true
                                    statusText = "✅ الصفحة محملة — انتظار Angular..."
                                } else {
                                    android.util.Log.e("SessionKeeper", "⚠️ PAGE FINISHED but URL suspicious: $url")
                                }

                                view?.evaluateJavascript(ANTI_DETECT_JS, null)
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

private val ANTI_DETECT_JS = """
    (function() {
        if (window.__antiDetect) return;
        window.__antiDetect = true;
        try {
            Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
            if (!window.chrome) window.chrome = { runtime: {} };
            if (navigator.plugins.length === 0) {
                const fakePlugins = [
                    { name: 'Chrome PDF Plugin', filename: 'internal-pdf-viewer', description: 'Portable Document Format' },
                    { name: 'Chrome PDF Viewer', filename: 'mhjfbmdgcfjbbpaeojofohoefgiehjai', description: '' },
                    { name: 'Native Client', filename: 'internal-nacl-plugin', description: '' }
                ];
                Object.defineProperty(navigator, 'plugins', { get: () => fakePlugins });
            }
            Object.defineProperty(navigator, 'languages', { get: () => ['ar-SA', 'ar', 'en-US', 'en'] });
            Object.defineProperty(navigator, 'vendor', { get: () => 'Google Inc.' });
            Object.defineProperty(navigator, 'platform', { get: () => 'Win32' });
            if (navigator.hardwareConcurrency && navigator.hardwareConcurrency < 4) {
                Object.defineProperty(navigator, 'hardwareConcurrency', { get: () => 8 });
            }
            try {
                Object.defineProperty(screen, 'width', { get: () => 1920 });
                Object.defineProperty(screen, 'height', { get: () => 1080 });
                Object.defineProperty(screen, 'availWidth', { get: () => 1920 });
                Object.defineProperty(screen, 'availHeight', { get: () => 1040 });
            } catch(e) {}
            if (navigator.userAgentData) {
                Object.defineProperty(navigator, 'userAgentData', { get: () => undefined });
            }
            SaudiaBridge.onLog('Anti-detection installed');
        } catch(e) {
            SaudiaBridge.onLog('Anti-detect error: ' + e.message);
        }
    })();
""".trimIndent()

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
