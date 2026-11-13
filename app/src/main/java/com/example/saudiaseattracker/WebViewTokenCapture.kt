package com.example.saudiaseattracker

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

@SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
@Composable
fun WebViewTokenCapture(
    reese84Token: String,
    autoMode: Boolean = false,     // ← وضع تلقائي: لا تفاعل مطلوب
    onSuccess: (jwt: String, sid: String) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    var status by remember { mutableStateOf("🔄 حقن reese84...") }
    var currentUrl by remember { mutableStateOf("") }
    var hookInstalled by remember { mutableStateOf(false) }
    var autoAttempts by remember { mutableIntStateOf(0) }
    val captured = remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var showManualFallback by remember { mutableStateOf(false) }

    // ═══ حقن reese84 في CookieManager ═══
    LaunchedEffect(Unit) {
        try {
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            val cookieValue = "reese84=$reese84Token; Domain=.saudia.com; Path=/; Secure"
            cm.setCookie("https://www.saudia.com", cookieValue)
            cm.setCookie("https://saudia.com", cookieValue)
            cm.setCookie("https://dapi.saudia.com", cookieValue)
            cm.flush()

            DebugBus.emit(DebugEntry(
                source = DebugSource.APP,
                title = "🍪 reese84 محقون (auto=${autoMode})",
                body = "autoMode=$autoMode"
            ))
            status = "✅ reese84 محقون"
        } catch (e: Exception) {
            status = "❌ فشل الحقن: ${e.message}"
        }
    }

    // ═══ وضع تلقائي: محاولة فتح صفحات تُطلق /token/init ═══
    LaunchedEffect(autoMode) {
        if (!autoMode) return@LaunchedEffect
        // ننتظر حتى يُحمّل WebView
        kotlinx.coroutines.delay(5000)
        val wv = webViewRef ?: return@LaunchedEffect

        // محاولة 1: صفحة البحث
        val autoUrls = listOf(
            "https://www.saudia.com/ar-SA/booking/search",
            "https://www.saudia.com/ar-SA/booking",
            "https://www.saudia.com/ar-SA/booking/passengerDetails"
        )

        for (url in autoUrls) {
            if (captured.value) break
            if (!autoMode) break
            autoAttempts++
            status = "🔄 محاولة تلقائية #$autoAttempts: ${url.takeLast(30)}"
            DebugBus.emit(DebugEntry(
                source = DebugSource.APP,
                title = "🔄 auto-nav #$autoAttempts",
                body = "url=$url"
            ))
            wv.post { wv.loadUrl(url) }
            kotlinx.coroutines.delay(12000)
        }

        if (!captured.value) {
            // فشل الوضع التلقائي → أظهر للمستخدم
            showManualFallback = true
            status = "⚠️ لم يُلتقط JWT تلقائياً — تفاعل مع الصفحة"
        }
    }

    // ═══ JS Bridge ═══
    val bridge = remember {
        object {
            @JavascriptInterface
            fun onJwt(token: String, sid: String) {
                if (captured.value) return
                captured.value = true
                status = "🎉 JWT تم التقاطه!"
                Log.d("WebViewCapture", "🎯 JWT: ${token.take(40)}...")
                DebugBus.emit(DebugEntry(
                    source = DebugSource.APP,
                    title = "🎉 JWT من WebView!",
                    body = "jwt.prefix=${token.take(60)}...\nsid=$sid\nautoAttempts=$autoAttempts"
                ))
                onSuccess(token, sid)
            }
            @JavascriptInterface
            fun onLog(msg: String) {
                if (msg.contains("token", true) || msg.contains("hook", true) ||
                    msg.contains("XHR", true) || msg.contains("FETCH", true)) {
                    Log.e("WebViewCapture", "📝 $msg")
                }
            }
        }
    }

    // ═══ وضع تلقائي: عرض صغير فقط ═══
    if (autoMode && !showManualFallback) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                modifier = Modifier.size(1.dp),  // ← 1x1 بكسل - مخفي
                factory = { ctx ->
                    createWebView(ctx, bridge, { url -> currentUrl = url }, {
                        hookInstalled = true
                    }).also { webViewRef = it }
                }
            )
            // Overlay صغير لإعلام المستخدم
            Surface(
                modifier = Modifier.align(androidx.compose.ui.Alignment.TopCenter)
                    .padding(top = 40.dp)
                    .padding(horizontal = 16.dp),
                color = Color(0xFF2196F3),
                shape = MaterialTheme.shapes.medium,
                shadowElevation = 8.dp
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(status, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    if (autoAttempts > 0) {
                        Text(
                            "محاولات: $autoAttempts/3",
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 10.sp
                        )
                    }
                    TextButton(onClick = onCancel) {
                        Text("✖️ إلغاء", color = Color.White, fontSize = 11.sp)
                    }
                }
            }
        }
        return
    }

    // ═══ وضع يدوي (أو fallback) ═══
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Card(
            Modifier.fillMaxWidth().padding(8.dp),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    captured.value -> Color(0xFF00C853)
                    showManualFallback -> Color(0xFFD32F2F)
                    hookInstalled -> Color(0xFF2196F3)
                    else -> Color(0xFF607D8B)
                }
            )
        ) {
            Column(Modifier.padding(10.dp)) {
                Text(status, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                if (currentUrl.isNotBlank()) {
                    Text(
                        "URL: ${currentUrl.takeLast(50)}",
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 9.sp, maxLines = 1
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (hookInstalled) "✅ Hook" else "⏳ Hook",
                        color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(if (captured.value) "✅ JWT" else "⏳ JWT",
                        color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { ctx ->
                createWebView(ctx, bridge, { url -> currentUrl = url }, {
                    hookInstalled = true
                }).also { webViewRef = it }
            }
        )

        Card(
            Modifier.fillMaxWidth().padding(8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(Modifier.padding(10.dp)) {
                Text("📋 التعليمات:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text(
                    "1. ابحث عن رحلة (JED → AHB)\n" +
                    "2. اختر الرحلة\n" +
                    "3. JWT يُلتقط عند صفحة المسافرين",
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text("✖️ إلغاء", fontSize = 12.sp)
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createWebView(
    ctx: android.content.Context,
    bridge: Any,
    onUrlChange: (String) -> Unit,
    onHookInstalled: () -> Unit
): WebView {
    return WebView(ctx).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Safari/537.36"
        settings.mediaPlaybackRequiresUserGesture = false

        addJavascriptInterface(bridge, "SaudiaBridge")
        webChromeClient = object : WebChromeClient() {}

        webViewClient = object : WebViewClient() {
            override fun onPageStarted(v: WebView?, url: String?, fav: Bitmap?) {
                super.onPageStarted(v, url, fav)
                onUrlChange(url ?: "")
                v?.evaluateJavascript(HOOK_JS, null)
            }
            override fun onPageFinished(v: WebView?, url: String?) {
                super.onPageFinished(v, url)
                onUrlChange(url ?: "")
                v?.evaluateJavascript(HOOK_JS, null)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    v?.evaluateJavascript(HOOK_JS, null)
                    onHookInstalled()
                }, 2000)
            }
        }
        loadUrl("https://www.saudia.com/ar-SA")
    }
}

private const val HOOK_JS = """
    (function() {
        if (window.__hookInstalled && window.__hookInstalledAt > Date.now() - 60000) return;
        window.__hookInstalled = true;
        window.__hookInstalledAt = Date.now();
        if (!window.__origFetch) {
            window.__origFetch = window.fetch;
            window.fetch = function(input, init) {
                var url = typeof input === 'string' ? input : (input && input.url) || '';
                var isTok = url.indexOf('/token/init') > -1 || url.indexOf('/token/initialization') > -1;
                if (isTok) SaudiaBridge.onLog('FETCH: ' + url.substring(0, 150));
                var p = window.__origFetch.apply(this, arguments);
                if (isTok) {
                    p.then(function(r) {
                        r.clone().text().then(function(t) {
                            try {
                                var d = JSON.parse(t);
                                SaudiaBridge.onLog('TOK RESP: ' + t.substring(0, 200));
                                if (d.access_token) SaudiaBridge.onJwt('Bearer ' + d.access_token, d.session_id || '');
                            } catch(e) {}
                        });
                    });
                }
                return p;
            };
        }
        if (!window.__origXHR) {
            window.__origXHR = true;
            var oO = XMLHttpRequest.prototype.open;
            var oS = XMLHttpRequest.prototype.send;
            XMLHttpRequest.prototype.open = function(m, u) { this.__u = u; return oO.apply(this, arguments); };
            XMLHttpRequest.prototype.send = function() {
                var s = this;
                var isTok = s.__u && (s.__u.indexOf('/token/init') > -1 || s.__u.indexOf('/token/initialization') > -1);
                if (isTok) SaudiaBridge.onLog('XHR: ' + s.__u.substring(0, 150));
                if (isTok) s.addEventListener('load', function() {
                    try {
                        var d = JSON.parse(s.responseText);
                        SaudiaBridge.onLog('XHR RESP: ' + s.responseText.substring(0, 200));
                        if (d.access_token) SaudiaBridge.onJwt('Bearer ' + d.access_token, d.session_id || '');
                    } catch(e) {}
                });
                return oS.apply(this, arguments);
            };
        }
        SaudiaBridge.onLog('hook @ ' + location.href);
    })();
"""
