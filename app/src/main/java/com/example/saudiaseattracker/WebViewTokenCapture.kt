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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
    onSuccess: (jwt: String, sid: String) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    var status by remember { mutableStateOf("🔄 جاري حقن reese84...") }
    var currentUrl by remember { mutableStateOf("") }
    var isLoaded by remember { mutableStateOf(false) }
    var hookInstalled by remember { mutableStateOf(false) }
    val captured = remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            val cookieValue = "reese84=$reese84Token; Domain=.saudia.com; Path=/; Secure"
            cm.setCookie("https://www.saudia.com", cookieValue)
            cm.setCookie("https://saudia.com", cookieValue)
            cm.setCookie("https://dapi.saudia.com", cookieValue)
            cm.flush()

            val check = cm.getCookie("https://www.saudia.com")
            DebugBus.emit(DebugEntry(
                source = DebugSource.APP,
                title = "🍪 reese84 محقون في WebView",
                body = "cookieValue.len=${cookieValue.length}\ncheck.has_reese=${check?.contains("reese84") == true}"
            ))
            status = "✅ reese84 محقون — جاري التحميل..."
        } catch (e: Exception) {
            status = "❌ فشل الحقن: ${e.message}"
        }
    }

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
                    body = "jwt.prefix=${token.take(60)}...\nsid=$sid"
                ))
                onSuccess(token, sid)
            }
            @JavascriptInterface
            fun onLog(msg: String) {
                Log.e("WebViewCapture", "📝 JS: $msg")
                // نسجّل فقط الأحداث المهمة لتجنب الفوضى
                if (msg.contains("token", ignoreCase = true) ||
                    msg.contains("JWT", ignoreCase = true) ||
                    msg.contains("hook", ignoreCase = true) ||
                    msg.contains("XHR", ignoreCase = true) ||
                    msg.contains("FETCH", ignoreCase = true)
                ) {
                    DebugBus.emit(DebugEntry(
                        source = DebugSource.APP,
                        title = "📝 WebView JS",
                        body = msg.take(500)
                    ))
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        // ═══ شريط الحالة ═══
        Card(
            Modifier.fillMaxWidth().padding(8.dp),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    captured.value -> Color(0xFF00C853)
                    hookInstalled -> Color(0xFF2196F3)
                    isLoaded -> Color(0xFFFFA726)
                    else -> Color(0xFF607D8B)
                }
            )
        ) {
            Column(Modifier.padding(10.dp)) {
                Text(status, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                if (currentUrl.isNotBlank()) {
                    Text(
                        "URL: ${currentUrl.takeLast(60)}",
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 9.sp,
                        maxLines = 1
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        if (hookInstalled) "✅ Hook مثبّت" else "⏳ Hook...",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (captured.value) "✅ JWT" else "⏳ JWT",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // ═══ WebView ═══
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Safari/537.36"

                    addJavascriptInterface(bridge, "SaudiaBridge")
                    webChromeClient = object : WebChromeClient() {}

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(v: WebView?, url: String?, fav: Bitmap?) {
                            super.onPageStarted(v, url, fav)
                            currentUrl = url ?: ""
                            // ✅ تثبيت الـ hook مبكراً — بدون فحص
                            v?.evaluateJavascript(HOOK_JS, null)
                        }
                        override fun onPageFinished(v: WebView?, url: String?) {
                            super.onPageFinished(v, url)
                            currentUrl = url ?: ""
                            isLoaded = true

                            // تثبيت الـ hook بعد اكتمال التحميل أيضاً (احتياطي)
                            v?.evaluateJavascript(HOOK_JS, null)

                            // إعادة التثبيت بعد 2 ثانية (SPA قد يعيد تعريف fetch)
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                v?.evaluateJavascript(HOOK_JS, null)
                                hookInstalled = true
                                if (!captured.value) {
                                    status = "✅ الصفحة جاهزة — ابحث عن رحلة"
                                }
                            }, 2000)
                        }
                    }
                    loadUrl("https://www.saudia.com/ar-SA")
                }
            }
        )

        // ═══ التعليمات ═══
        Card(
            Modifier.fillMaxWidth().padding(8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(Modifier.padding(10.dp)) {
                Text("📋 التعليمات:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text(
                    "1. ابحث عن رحلة (مثلاً JED → AHB)\n" +
                    "2. اختر الرحلة من النتائج\n" +
                    "3. عند فتح صفحة المسافرين → JWT يُلتقط تلقائياً\n" +
                    "4. ستعود للشاشة الرئيسية تلقائياً",
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.weight(1f)
                    ) { Text("✖️ إلغاء", fontSize = 12.sp) }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  JavaScript Hook — يلتقط /token/init من fetch و XHR
// ═══════════════════════════════════════════════════════════
private const val HOOK_JS = """
    (function() {
        if (window.__hookInstalled && window.__hookInstalledAt > Date.now() - 60000) return;
        window.__hookInstalled = true;
        window.__hookInstalledAt = Date.now();

        // ─── اعتراض fetch ───
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
                                SaudiaBridge.onLog('FETCH RESP: ' + t.substring(0, 300));
                                if (d.access_token) {
                                    SaudiaBridge.onJwt('Bearer ' + d.access_token, d.session_id || '');
                                }
                            } catch(e) { SaudiaBridge.onLog('FETCH parse err: ' + e); }
                        });
                    });
                }
                return p;
            };
        }

        // ─── اعتراض XHR ───
        if (!window.__origXHR) {
            window.__origXHR = true;
            var oO = XMLHttpRequest.prototype.open;
            var oS = XMLHttpRequest.prototype.send;
            XMLHttpRequest.prototype.open = function(m, u) {
                this.__u = u;
                return oO.apply(this, arguments);
            };
            XMLHttpRequest.prototype.send = function() {
                var s = this;
                var isTok = s.__u && (s.__u.indexOf('/token/init') > -1 || s.__u.indexOf('/token/initialization') > -1);
                if (isTok) SaudiaBridge.onLog('XHR: ' + s.__u.substring(0, 150));
                if (isTok) {
                    s.addEventListener('load', function() {
                        try {
                            var d = JSON.parse(s.responseText);
                            SaudiaBridge.onLog('XHR RESP: ' + s.responseText.substring(0, 300));
                            if (d.access_token) {
                                SaudiaBridge.onJwt('Bearer ' + d.access_token, d.session_id || '');
                            }
                        } catch(e) {}
                    });
                }
                return oS.apply(this, arguments);
            };
        }

        SaudiaBridge.onLog('hook @ ' + location.href);
    })();
"""
