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
    onSuccess: (jwt: String, sid: String) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    var status by remember { mutableStateOf("🔄 جاري حقن reese84...") }
    var currentUrl by remember { mutableStateOf("") }
    var isChallengePage by remember { mutableStateOf<Boolean?>(null) }
    var isLoaded by remember { mutableStateOf(false) }
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
            Log.d("WebViewCapture", "🍪 Cookies after inject: ${check?.take(200)}")
            DebugBus.emit(DebugEntry(
                source = DebugSource.APP,
                title = "🍪 reese84 محقون",
                body = "cookieValue.len=${cookieValue.length}\ncheck=${check?.take(300)}"
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
                DebugBus.emit(DebugEntry(
                    source = DebugSource.APP,
                    title = "📝 WebView JS",
                    body = msg.take(1000)
                ))
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Card(
            Modifier.fillMaxWidth().padding(8.dp),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    captured.value -> Color(0xFF00C853)
                    isChallengePage == true -> Color(0xFFD32F2F)
                    isLoaded -> Color(0xFF2196F3)
                    else -> Color(0xFFFFA726)
                }
            )
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(status, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                if (currentUrl.isNotBlank()) {
                    Text(
                        "URL: ${currentUrl.takeLast(60)}",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 10.sp,
                        maxLines = 1
                    )
                }
                if (isChallengePage != null) {
                    Text(
                        if (isChallengePage == true) "❌ صفحة تحدي" else "✅ صفحة حقيقية",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
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

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(v: WebView?, url: String?, fav: Bitmap?) {
                            super.onPageStarted(v, url, fav)
                            currentUrl = url ?: ""
                            status = "📄 تحميل..."
                        }
                        override fun onPageFinished(v: WebView?, url: String?) {
                            super.onPageFinished(v, url)
                            currentUrl = url ?: ""
                            isLoaded = true
                            v?.evaluateJavascript(
                                "(function(){" +
                                "var b = document.body ? document.body.innerText : '';" +
                                "var c = b.indexOf('Pardon Our Interruption') > -1 || " +
                                "        document.querySelector('script[src*=\"_Incapsula_Resource\"]') !== null;" +
                                "SaudiaBridge.onLog('isChallenge=' + c + ' bodyLen=' + b.length);" +
                                "return c;" +
                                "})()"
                            ) { result ->
                                val isCh = result?.trim()?.removeSurrounding("\"") == "true"
                                isChallengePage = isCh
                                if (isCh) {
                                    status = "❌ لا تزال صفحة التحدي"
                                } else {
                                    status = "✅ صفحة حقيقية — حقن JS لاعتراض /token/init"
                                    v?.evaluateJavascript(HOOK_JS, null)
                                }
                            }
                        }
                    }
                    loadUrl("https://www.saudia.com/ar-SA")
                }
            }
        )

        Card(
            Modifier.fillMaxWidth().padding(8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(Modifier.padding(10.dp)) {
                Text("📋 التعليمات:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text(
                    "1. لو 'صفحة حقيقية' ✅ → ابحث عن رحلة، اخترها، ادخل بيانات المسافر\n" +
                    "2. JWT يُلتقط تلقائياً\n" +
                    "3. لو 'صفحة تحدي' ❌ → أرسل السجل، سنغيّر التكتيك",
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text("إلغاء")
                }
            }
        }
    }
}

private const val HOOK_JS = """
    (function() {
        if (window.__hook) return;
        window.__hook = true;
        var origFetch = window.fetch;
        window.fetch = function(input, init) {
            var url = typeof input === 'string' ? input : (input && input.url) || '';
            var isTok = url.indexOf('/token/init') > -1 || url.indexOf('/token/initialization') > -1;
            SaudiaBridge.onLog('FETCH: ' + url.substring(0, 100) + ' isTok=' + isTok);
            var p = origFetch.apply(this, arguments);
            if (isTok) {
                p.then(function(r) {
                    r.clone().text().then(function(t) {
                        try {
                            var d = JSON.parse(t);
                            SaudiaBridge.onLog('TOK RESP: ' + t.substring(0, 200));
                            if (d.access_token) SaudiaBridge.onJwt('Bearer ' + d.access_token, d.session_id || '');
                        } catch(e) { SaudiaBridge.onLog('parse err: ' + e); }
                    });
                });
            }
            return p;
        };
        var oO = XMLHttpRequest.prototype.open, oS = XMLHttpRequest.prototype.send;
        XMLHttpRequest.prototype.open = function(m, u) { this.__u = u; return oO.apply(this, arguments); };
        XMLHttpRequest.prototype.send = function() {
            var s = this;
            var isTok = s.__u && (s.__u.indexOf('/token/init') > -1 || s.__u.indexOf('/token/initialization') > -1);
            SaudiaBridge.onLog('XHR: ' + (s.__u||'').substring(0, 100) + ' isTok=' + isTok);
            if (isTok) s.addEventListener('load', function() {
                try {
                    var d = JSON.parse(s.responseText);
                    SaudiaBridge.onLog('XHR RESP: ' + s.responseText.substring(0, 200));
                    if (d.access_token) SaudiaBridge.onJwt('Bearer ' + d.access_token, d.session_id || '');
                } catch(e) {}
            });
            return oS.apply(this, arguments);
        };
        SaudiaBridge.onLog('hook installed @ ' + location.href);
    })();
"""
