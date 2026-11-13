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
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

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
    var jwtReady by remember { mutableStateOf(false) }
    var sidReady by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

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
        } catch (e: Exception) {
            status = "❌ ${e.message}"
        }
    }

    val bridge = remember {
        object {
            var lastToken: String? = null
            var lastSid: String? = null

            @JavascriptInterface
            fun onJwt(token: String, sid: String) {
                if (!token.startsWith("Bearer ") && !token.startsWith("eyJ")) return
                if (token == lastToken && sid == lastSid) return
                lastToken = token
                lastSid = sid
                val clean = if (token.startsWith("Bearer ")) token else "Bearer $token"

                if (!jwtReady) {
                    jwtReady = true
                    Log.d("WebViewCapture", "🎯 JWT len=${clean.length}")
                }
                if (sid.isNotBlank() && !sidReady) {
                    sidReady = true
                    Log.d("WebViewCapture", "🆔 sid: ${sid.take(8)}...")
                }

                status = when {
                    jwtReady && sidReady -> "🎉 JWT + sid جاهزان!"
                    jwtReady -> "🎉 JWT! (sid ⏳)"
                    else -> "🎉 JWT تم التقاطه!"
                }

                DebugBus.emit(DebugEntry(
                    source = DebugSource.APP,
                    title = "🎉 onJwt",
                    body = "jwt.len=${clean.length}\nsid=${sid.ifBlank { "(empty)" }}\njwtReady=$jwtReady sidReady=$sidReady"
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

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Card(
            Modifier.fillMaxWidth().padding(8.dp),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    jwtReady && sidReady -> Color(0xFF00C853)
                    jwtReady -> Color(0xFF9C27B0)
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
                        fontSize = 9.sp, maxLines = 1
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        if (hookInstalled) "✅ Hook" else "⏳ Hook",
                        color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold
                    )
                    Text(
                        "📊 JS: $eventsSeen",
                        color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (jwtReady) "✅ JWT" else "⏳ JWT",
                        color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (sidReady) "✅ sid" else "⏳ sid",
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

                    if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                        try {
                            WebViewCompat.addDocumentStartJavaScript(
                                this, HOOK_JS,
                                setOf("https://www.saudia.com", "https://saudia.com")
                            )
                            hookInstalled = true
                        } catch (e: Exception) {
                            Log.e("WebViewCapture", "❌ addDocumentStartJavaScript: ${e.message}")
                        }
                    }

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(v: WebView?, url: String?, fav: Bitmap?) {
                            super.onPageStarted(v, url, fav)
                            currentUrl = url ?: ""
                            if (!hookInstalled) {
                                v?.evaluateJavascript(HOOK_JS, null)
                                hookInstalled = true
                            }
                        }
                        override fun onPageFinished(v: WebView?, url: String?) {
                            super.onPageFinished(v, url)
                            currentUrl = url ?: ""
                            v?.evaluateJavascript(HOOK_JS, null)
                        }
                    }
                    loadUrl("https://www.saudia.com/ar-SA")
                }.also { webViewRef = it }
            }
        )

        Card(
            Modifier.fillMaxWidth().padding(8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(Modifier.padding(10.dp)) {
                Text(
                    "🔍 الـ hook مثبّت. JWT + sid يُلتقطان تلقائياً.",
                    fontSize = 11.sp
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

private const val HOOK_JS = """
    (function() {
        if (window.__hookInstalled) return;
        window.__hookInstalled = true;

        function getSid() {
            try {
                return sessionStorage.getItem('saudia-session-id') || 
                       localStorage.getItem('saudia-session-id') || '';
            } catch(e) { return ''; }
        }

        function captureToken(token) {
            var sid = getSid();
            SaudiaBridge.onJwt(token, sid);
            if (!sid) {
                var attempts = 0;
                var checkSid = setInterval(function() {
                    attempts++;
                    var s = getSid();
                    if (s) {
                        clearInterval(checkSid);
                        SaudiaBridge.onJwt(token, s);
                    }
                    if (attempts > 20) clearInterval(checkSid);
                }, 300);
            }
        }

        try {
            var origFetch = window.fetch;
            window.fetch = function(input, init) {
                try {
                    var url = typeof input === 'string' ? input : (input && input.url) || '';
                    var isTok = url.indexOf('/token/init') > -1 || url.indexOf('/token/initialization') > -1;
                    if (isTok) SaudiaBridge.onLog('FETCH: ' + url.substring(0, 150));
                    var p = origFetch.apply(this, arguments);
                    if (isTok) {
                        p.then(function(r) {
                            r.clone().text().then(function(t) {
                                try {
                                    var d = JSON.parse(t);
                                    SaudiaBridge.onLog('RESP: ' + t.substring(0, 300));
                                    if (d.access_token) {
                                        captureToken('Bearer ' + d.access_token);
                                    }
                                } catch(e) {}
                            });
                        }).catch(function(e){});
                    }
                    return p;
                } catch(e) {
                    return origFetch.apply(this, arguments);
                }
            };
        } catch(e) { SaudiaBridge.onLog('fetch hook err: ' + e); }

        try {
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
                    var isTok = url.indexOf('/token/init') > -1 || url.indexOf('/token/initialization') > -1;
                    if (isTok) SaudiaBridge.onLog('XHR: ' + url.substring(0, 150));
                    if (isTok) {
                        s.addEventListener('load', function() {
                            try {
                                SaudiaBridge.onLog('XHR RESP: ' + s.responseText.substring(0, 300));
                                var d = JSON.parse(s.responseText);
                                if (d.access_token) {
                                    captureToken('Bearer ' + d.access_token);
                                }
                            } catch(e) {}
                        });
                    }
                } catch(e) {}
                return origSend.apply(this, arguments);
            };
        } catch(e) { SaudiaBridge.onLog('XHR hook err: ' + e); }

        setInterval(function() {
            try {
                var keys = ['saudia-access-token', 'access_token', 'accessToken', 'authorization', 'jwt', 'token'];
                for (var i = 0; i < keys.length; i++) {
                    var v = sessionStorage.getItem(keys[i]) || localStorage.getItem(keys[i]);
                    if (v && typeof v === 'string' && v.indexOf('eyJ') > -1) {
                        captureToken(v.indexOf('Bearer ') === 0 ? v : 'Bearer ' + v);
                        return;
                    }
                }
            } catch(e) {}
        }, 3000);

        SaudiaBridge.onLog('HOOK INSTALLED @ ' + location.href);
    })();
"""

private const val SCAN_STORAGE_JS = """
    (function(){
        var found = [];
        var sid = '';
        try {
            sid = sessionStorage.getItem('saudia-session-id') || localStorage.getItem('saudia-session-id') || '';
            found.push('SID: ' + sid);
            for (var i = 0; i < localStorage.length; i++) {
                var k = localStorage.key(i);
                var v = localStorage.getItem(k);
                if (v && v.indexOf('eyJ') > -1) {
                    found.push('LS[' + k + ']=' + v.substring(0, 100));
                    SaudiaBridge.onJwt(v.indexOf('Bearer ') === 0 ? v : 'Bearer ' + v, sid);
                }
            }
            for (var i = 0; i < sessionStorage.length; i++) {
                var k = sessionStorage.key(i);
                var v = sessionStorage.getItem(k);
                if (v && v.indexOf('eyJ') > -1) {
                    found.push('SS[' + k + ']=' + v.substring(0, 100));
                    SaudiaBridge.onJwt(v.indexOf('Bearer ') === 0 ? v : 'Bearer ' + v, sid);
                }
            }
        } catch(e) {}
        SaudiaBridge.onLog('STORAGE SCAN:\n' + found.join('\n'));
    })();
"""
