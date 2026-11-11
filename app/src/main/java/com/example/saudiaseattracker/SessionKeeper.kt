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
import android.webkit.WebStorage
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

    LaunchedEffect(Unit) {
        delay(150_000)
        if (!captured) {
            android.util.Log.e("SessionKeeper", "⏱️ TIMEOUT 150s (bodyLen=$lastBodyLen)")
            statusText = "⚠️ لم نلتقط (bodyLen=$lastBodyLen)"
            onComplete()
        }
    }

    // ⭐ Polling بعد pageFinished فقط، بفواصل عشوائية
    LaunchedEffect(pageFinished, captured) {
        if (!pageFinished || captured) return@LaunchedEffect

        val initialWait = Random.nextLong(8000, 15000)
        statusText = "⏳ انتظار Angular (${initialWait / 1000}s)..."
        android.util.Log.e("SessionKeeper", "⏳ Waiting ${initialWait}ms for Angular")
        delay(initialWait)

        if (captured) return@LaunchedEffect

        android.util.Log.e("SessionKeeper", "🔍 SLOW POLLING STARTED (max 6 polls)")

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

                        // ⭐ تشخيص إضافي
                        var diag = {
                            rs: document.readyState || '?',
                            ngV: ngV,
                            appRoot: !!document.querySelector('app-root,saudia-web-app'),
                            bodyLen: document.body ? document.body.innerHTML.length : 0,
                            scripts: document.scripts.length,
                            hasWvMarker: document.body ? document.body.innerHTML.indexOf('incapsula') > -1 : false,
                            hasReese: document.cookie.indexOf('reese84') > -1,
                            uaBrands: (navigator.userAgentData && navigator.userAgentData.brands) ? navigator.userAgentData.brands.map(function(b){return b.brand;}).join(',') : 'none',
                            ua: (navigator.userAgent || '').substring(0, 100),
                            challengeDetected: document.body ? (document.body.innerHTML.indexOf('Checking') > -1 || document.body.innerHTML.indexOf('robot') > -1 || document.body.innerHTML.indexOf('verify') > -1) : false
                        };
                        SaudiaBridge.onPollResult(JSON.stringify(diag));
                    } catch(e) {
                        SaudiaBridge.onPollError(e.message);
                    }
                })();
            """.trimIndent(), null)

            val nextWait = Random.nextLong(4000, 8001)
            delay(nextWait)
        }

        android.util.Log.e("SessionKeeper", "Polling finished: captured=$captured")
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
                            return
                        }
                    }
                } catch (e: Exception) {
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
                    lastBodyLen = obj.optInt("bodyLen", 0)
                    val ngV = obj.optString("ngV", "none")
                    if (lastBodyLen > 10000 || (ngV != "none" && ngV != "empty")) {
                        angularDetected = true
                    }
                    android.util.Log.e("SessionKeeper", "📊 Poll $pollCount: $json")
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

                        // ⭐⭐⭐ مسح كامل لبيانات WebView (reese84 مسموم)
                        clearCache(true)
                        clearHistory()
                        clearFormData()
                        val cm = CookieManager.getInstance()
                        cm.removeAllCookies(null)
                        cm.flush()
                        WebStorage.getInstance().deleteAllData()
                        android.util.Log.e("SessionKeeper", "🧹 WebView data cleared (cookies + storage)")

                        // UA سطح المكتب
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
                        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT

                        WebView.setWebContentsDebuggingEnabled(false)

                        addJavascriptInterface(bridge, "SaudiaBridge")

                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                                msg?.let {
                                    if (it.messageLevel() >= ConsoleMessage.MessageLevel.ERROR) {
                                        android.util.Log.e("SessionKeeper",
                                            "📺 ${it.message().take(200)} @${it.sourceId()?.substringAfterLast('/') ?: ""}")
                                    }
                                }
                                return true
                            }
                            override fun onJsAlert(v: WebView?, u: String?, m: String?, r: JsResult?): Boolean { r?.confirm(); return true }
                            override fun onJsConfirm(v: WebView?, u: String?, m: String?, r: JsResult?): Boolean { r?.confirm(); return true }
                            override fun onJsPrompt(v: WebView?, u: String?, m: String?, d: String?, r: JsPromptResult?): Boolean { r?.confirm(d ?: ""); return true }
                        }

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                android.util.Log.e("SessionKeeper", "🌐 PAGE STARTED: $url")
                                // ⭐⭐⭐ anti-detect أولاً قبل أي شيء آخر ⭐⭐⭐
                                view?.evaluateJavascript(ANTI_DETECT_JS, null)
                                view?.evaluateJavascript(SESSION_HOOK_JS, null)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                android.util.Log.e("SessionKeeper", "✅ PAGE FINISHED: $url")
                                if (url != null && url.contains("saudia.com") && !url.contains("incident")) {
                                    pageFinished = true
                                    statusText = "✅ الصفحة محملة — انتظار Angular..."
                                }
                                // حقن anti-detect مرة أخرى بعد التحميل
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

// ══════════════════════════════════════════════════════════════
// Anti-detection — إخفاء WebView عن Imperva
// ══════════════════════════════════════════════════════════════
private val ANTI_DETECT_JS = """
    (function() {
        if (window.__antiDetect) return;
        window.__antiDetect = true;
        try {
            // 1. إخفاء webdriver
            Object.defineProperty(navigator, 'webdriver', { get: () => undefined });

            // 2. ⭐⭐⭐ الأهم: إخفاء "Android WebView" من brands ⭐⭐⭐
            if (navigator.userAgentData) {
                try {
                    Object.defineProperty(navigator.userAgentData, 'brands', {
                        get: () => [
                            { brand: 'Chromium', version: '120' },
                            { brand: 'Google Chrome', version: '120' },
                            { brand: 'Not-A.Brand', version: '99' }
                        ]
                    });
                    Object.defineProperty(navigator.userAgentData, 'mobile', { get: () => false });
                    Object.defineProperty(navigator.userAgentData, 'platform', { get: () => 'Windows' });
                } catch(e) {
                    // إذا فشل، احذف userAgentData كلياً
                    Object.defineProperty(navigator, 'userAgentData', { get: () => undefined });
                }
            }

            // 3. window.chrome (Chrome الحقيقي لديه هذا)
            if (!window.chrome) {
                window.chrome = {
                    runtime: {},
                    loadTimes: function() {},
                    csi: function() {},
                    app: {}
                };
            }

            // 4. plugins (Chrome الحقيقي: 3-5)
            if (navigator.plugins.length === 0) {
                const fakePlugins = [
                    { name: 'Chrome PDF Plugin', filename: 'internal-pdf-viewer', description: 'Portable Document Format' },
                    { name: 'Chrome PDF Viewer', filename: 'mhjfbmdgcfjbbpaeojofohoefgiehjai', description: '' },
                    { name: 'Native Client', filename: 'internal-nacl-plugin', description: '' }
                ];
                Object.defineProperty(navigator, 'plugins', { get: () => fakePlugins });
            }

            // 5. languages
            Object.defineProperty(navigator, 'languages', { get: () => ['ar-SA', 'ar', 'en-US', 'en'] });

            // 6. vendor
            Object.defineProperty(navigator, 'vendor', { get: () => 'Google Inc.' });

            // 7. platform (Win32 لسطح المكتب)
            Object.defineProperty(navigator, 'platform', { get: () => 'Win32' });

            // 8. hardwareConcurrency
            if (navigator.hardwareConcurrency && navigator.hardwareConcurrency < 4) {
                Object.defineProperty(navigator, 'hardwareConcurrency', { get: () => 8 });
            }

            // 9. screen (سطح مكتب)
            try {
                Object.defineProperty(screen, 'width', { get: () => 1920 });
                Object.defineProperty(screen, 'height', { get: () => 1080 });
                Object.defineProperty(screen, 'availWidth', { get: () => 1920 });
                Object.defineProperty(screen, 'availHeight', { get: () => 1040 });
                Object.defineProperty(screen, 'colorDepth', { get: () => 24 });
            } catch(e) {}

            // 10. devicePixelRatio
            try {
                Object.defineProperty(window, 'devicePixelRatio', { get: () => 1 });
            } catch(e) {}

            // 11. WebGL vendor/renderer
            try {
                const canvas = document.createElement('canvas');
                const gl = canvas.getContext('webgl') || canvas.getContext('experimental-webgl');
                if (gl) {
                    const ext = gl.getExtension('WEBGL_debug_renderer_info');
                    if (ext) {
                        const origGetParam = gl.getParameter.bind(gl);
                        gl.getParameter = function(p) {
                            if (p === ext.UNMASKED_VENDOR_WEBGL) return 'Google Inc. (Intel)';
                            if (p === ext.UNMASKED_RENDERER_WEBGL) return 'ANGLE (Intel, Intel(R) UHD Graphics 620 Direct3D11 vs_5_0 ps_5_0, D3D11)';
                            return origGetParam(p);
                        };
                    }
                }
            } catch(e) {}

            // 12. permissions
            if (navigator.permissions && navigator.permissions.query) {
                const origQuery = navigator.permissions.query.bind(navigator.permissions);
                navigator.permissions.query = (params) => {
                    if (params && params.name === 'notifications') {
                        return Promise.resolve({ state: 'granted' });
                    }
                    return origQuery(params);
                };
            }

            // 13. إخفاء علامات WebView في CSS
            try {
                const style = document.createElement('style');
                style.textContent = '::-webkit-scrollbar { width: 8px; }';
                document.head.appendChild(style);
            } catch(e) {}

            SaudiaBridge.onLog('Anti-detection installed');
        } catch(e) {
            SaudiaBridge.onLog('Anti-detect error: ' + e.message);
        }
    })();
""".trimIndent()

// ══════════════════════════════════════════════════════════════
// Token hooks
// ══════════════════════════════════════════════════════════════
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
