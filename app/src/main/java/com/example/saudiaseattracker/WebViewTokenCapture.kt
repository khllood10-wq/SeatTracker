package com.example.saudiaseattracker

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Build
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
    autoMode: Boolean = false,
    onSuccess: (jwt: String, sid: String) -> Unit,
    onCancel: () -> Unit
) {
    var status by remember { mutableStateOf("🔄 جاري الحقن...") }
    var currentUrl by remember { mutableStateOf("") }
    var hookInstalled by remember { mutableStateOf(false) }
    var eventsSeen by remember { mutableIntStateOf(0) }
    val captured = remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    // حقن reese84 في CookieManager
    LaunchedEffect(Unit) {
        try {
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            val cookieValue = "reese84=$reese84Token; Domain=.saudia.com; Path=/; Secure"
            cm.setCookie("https://www.saudia.com", cookieValue)
            cm.setCookie("https://saudia.com", cookieValue)
            cm.setCookie("https://dapi.saudia.com", cookieValue)
            cm.flush()
            status = "✅ reese84 محقون — تحميل الرئيسية..."
            DebugBus.emit(DebugEntry(
                source = DebugSource.APP,
                title = "🍪 reese84 injected",
                body = "len=${cookieValue.length}"
            ))
        } catch (e: Exception) {
            status = "❌ ${e.message}"
        }
    }

    // ⏰ مهلة 40 ثانية — إذا لم يُلتقط JWT، أعط المستخدم خيار fallback
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(40_000)
        if (!captured.value) {
            status = "⚠️ انتهت 40 ثانية — JWT لم يُلتقط. راجع السجل."
        }
    }

    val bridge = remember {
        object {
            @JavascriptInterface
            fun onJwt(token: String, sid: String) {
                if (captured.value) return
                if (!token.startsWith("Bearer ") && !token.startsWith("eyJ")) return
                captured.value = true
                val clean = if (token.startsWith("Bearer ")) token else "Bearer $token"
                status = "🎉 JWT تم التقاطه!"
                Log.d("WebViewCapture", "🎯 JWT len=${clean.length}")
                DebugBus.emit(DebugEntry(
                    source = DebugSource.APP,
                    title = "🎉 JWT captured from homepage!",
                    body = "jwt.prefix=${clean.take(60)}...\nsid=$sid"
                ))
                onSuccess(clean, sid)
            }
            @JavascriptInterface
            fun onLog(msg: String) {
                eventsSeen++
                Log.e("WebViewCapture", "📝 $msg")
                DebugBus.emit(DebugEntry(
                    source = DebugSource.APP,
                    title = "📝 WebView JS",
                    body = msg.take(800)
                ))
            }
        }
    }

    // ═══ عرض ═══
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        // شريط الحالة
        Card(
            Modifier.fillMaxWidth().padding(8.dp),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    captured.value -> Color(0xFF00C853)
                    eventsSeen > 3 -> Color(0xFF2196F3)
                    hookInstalled -> Color(0xFFFFA726)
                    else -> Color(0xFF607D8B)
                }
            )
        ) {
            Column(Modifier.padding(10.dp)) {
                Text(status, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                if (currentUrl.isNotBlank()) {
                    Text(
                        "URL: ${currentUrl.takeLast(55)}",
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 9.sp,
                        maxLines = 1
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        if (hookInstalled) "✅ Hook" else "⏳ Hook",
                        color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold
                    )
                    Text(
                        "📊 JS Events: $eventsSeen",
                        color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (captured.value) "✅ JWT" else "⏳ JWT",
                        color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold
                    )
                }
            }
        }

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

                    // ⭐ الأهم: تثبيت الـ hook قبل أي JS
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        addDocumentStartJavaScript(HOOK_JS, setOf("*"))
                        hookInstalled = true
                        Log.d("WebViewCapture", "✅ addDocumentStartJavaScript (Android 11+)")
                    }

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(v: WebView?, url: String?, fav: Bitmap?) {
                            super.onPageStarted(v, url, fav)
                            currentUrl = url ?: ""
                            // fallback للأجهزة الأقدم
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                                v?.evaluateJavascript(HOOK_JS, null)
                                hookInstalled = true
                            }
                        }
                        override fun onPageFinished(v: WebView?, url: String?) {
                            super.onPageFinished(v, url)
                            currentUrl = url ?: ""
                            // إعادة التثبيت احتياطاً (SPA قد يعيد تعريف fetch)
                            v?.evaluateJavascript(HOOK_JS, null)
                            // مسح Storage للبحث عن JWT مخزّن
                            v?.evaluateJavascript(SCAN_STORAGE_JS, null)
                        }
                    }
                    loadUrl("https://www.saudia.com/ar-SA")
                }.also { webViewRef = it }
            }
        )

        // تعليمات + مسح Storage يدوي
        Card(
            Modifier.fillMaxWidth().padding(8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(Modifier.padding(10.dp)) {
                Text(
                    "🔍 الـ hook مثبّت الآن قبل أي JS. يجب أن يلتقط /token/init تلقائياً.",
                    fontSize = 11.sp
                )
                Text(
                    "إذا لم يُلتقط خلال 30 ثانية → اضغط الزر أدناه",
                    fontSize = 11.sp, fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            webViewRef?.evaluateJavascript(SCAN_STORAGE_JS, null)
                            status = "🔍 فحص Storage..."
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("🔍 فحص Storage", fontSize = 11.sp) }
                    OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                        Text("✖️ إلغاء", fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  Hook يُثبّت قبل تحميل أي JS (addDocumentStartJavaScript)
// ═══════════════════════════════════════════════════════════
private const val HOOK_JS = """
    (function() {
        if (window.__hookInstalled) return;
        window.__hookInstalled = true;

        try {
            // ═══ 1) اعتراض fetch ═══
            var origFetch = window.fetch;
            window.fetch = function(input, init) {
                try {
                    var url = typeof input === 'string' ? input : (input && input.url) || '';
                    SaudiaBridge.onLog('FETCH: ' + url.substring(0, 150));
                    var isTok = url.indexOf('/token') > -1 || url.indexOf('/auth') > -1;
                    var p = origFetch.apply(this, arguments);
                    if (isTok) {
                        p.then(function(r) {
                            r.clone().text().then(function(t) {
                                try {
                                    SaudiaBridge.onLog('RESP: ' + t.substring(0, 300));
                                    var d = JSON.parse(t);
                                    if (d.access_token) SaudiaBridge.onJwt('Bearer ' + d.access_token, d.session_id || '');
                                    if (d.token && typeof d.token === 'string' && d.token.length > 50) {
                                        SaudiaBridge.onJwt('Bearer ' + d.token, d.session_id || '');
                                    }
                                } catch(e) {}
                            });
                        }).catch(function(e){});
                    }
                    return p;
                } catch(e) {
                    SaudiaBridge.onLog('FETCH ERR: ' + e);
                    return origFetch.apply(this, arguments);
                }
            };
        } catch(e) { SaudiaBridge.onLog('fetch hook err: ' + e); }

        try {
            // ═══ 2) اعتراض XHR ═══
            var origOpen = XMLHttpRequest.prototype.open;
            var origSend = XMLHttpRequest.prototype.send;
            XMLHttpRequest.prototype.open = function(m, u) {
                this.__u = u;
                return origOpen.apply(this, arguments);
            };
            XMLHttpRequest.prototype.send = function() {
                try {
                    var s = this;
                    var url = s.__u || '';
                    SaudiaBridge.onLog('XHR: ' + url.substring(0, 150));
                    var isTok = url.indexOf('/token') > -1 || url.indexOf('/auth') > -1;
                    if (isTok) {
                        s.addEventListener('load', function() {
                            try {
                                SaudiaBridge.onLog('XHR RESP: ' + s.responseText.substring(0, 300));
                                var d = JSON.parse(s.responseText);
                                if (d.access_token) SaudiaBridge.onJwt('Bearer ' + d.access_token, d.session_id || '');
                                if (d.token && typeof d.token === 'string' && d.token.length > 50) {
                                    SaudiaBridge.onJwt('Bearer ' + d.token, d.session_id || '');
                                }
                            } catch(e) {}
                        });
                    }
                } catch(e) {}
                return origSend.apply(this, arguments);
            };
        } catch(e) { SaudiaBridge.onLog('XHR hook err: ' + e); }

        // ═══ 3) فحص Storage كل 3 ثوانٍ ═══
        setInterval(function() {
            try {
                var keys = ['access_token', 'accessToken', 'saudia-access-token', 'authorization', 'jwt', 'token'];
                for (var i = 0; i < keys.length; i++) {
                    var v = sessionStorage.getItem(keys[i]) || localStorage.getItem(keys[i]);
                    if (v && typeof v === 'string' && v.indexOf('eyJ') > -1) {
                        var t = v.indexOf('Bearer ') === 0 ? v : 'Bearer ' + v;
                        SaudiaBridge.onJwt(t, '');
                        return;
                    }
                }
            } catch(e) {}
        }, 3000);

        SaudiaBridge.onLog('HOOK INSTALLED @ ' + location.href);
    })();
"""

// فحص يدوي للـ Storage
private const val SCAN_STORAGE_JS = """
    (function(){
        var found = [];
        try {
            for (var i = 0; i < localStorage.length; i++) {
                var k = localStorage.key(i);
                var v = localStorage.getItem(k);
                found.push('LS[' + k + ']=' + (v || '').substring(0, 100));
                if (v && v.indexOf('eyJ') > -1) {
                    SaudiaBridge.onJwt(v.indexOf('Bearer ') === 0 ? v : 'Bearer ' + v, '');
                }
            }
            for (var i = 0; i < sessionStorage.length; i++) {
                var k = sessionStorage.key(i);
                var v = sessionStorage.getItem(k);
                found.push('SS[' + k + ']=' + (v || '').substring(0, 100));
                if (v && v.indexOf('eyJ') > -1) {
                    SaudiaBridge.onJwt(v.indexOf('Bearer ') === 0 ? v : 'Bearer ' + v, '');
                }
            }
        } catch(e) {}
        SaudiaBridge.onLog('STORAGE SCAN:\n' + found.join('\n'));
    })();
"""
