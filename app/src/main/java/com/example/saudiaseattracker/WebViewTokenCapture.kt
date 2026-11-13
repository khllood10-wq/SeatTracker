package com.example.saudiaseattracker

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
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
    onLog: (String) -> Unit = {},
    onCancel: () -> Unit
) {
    var status by remember { mutableStateOf("⏳ جاري تجديد الجلسة...") }
    var jwtReady by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            val cookieValue = "reese84=$reese84Token; Domain=.saudia.com; Path=/; Secure"
            cm.setCookie("https://www.saudia.com", cookieValue)
            cm.setCookie("https://saudia.com", cookieValue)
            cm.setCookie("https://dapi.saudia.com", cookieValue)
            cm.flush()
        } catch (e: Exception) {
            status = "❌ ${e.message}"
        }
    }

    val bridge = remember {
        object {
            @JavascriptInterface
            fun onJwt(token: String, sid: String) {
                if (jwtReady) return
                if (!token.startsWith("Bearer ") && !token.startsWith("eyJ")) return
                jwtReady = true
                val clean = if (token.startsWith("Bearer ")) token else "Bearer $token"
                status = "✅ تم التقاط الجلسة!"
                onSuccess(clean, sid)
            }
            @JavascriptInterface
            fun onLog(msg: String) {
                onLog(msg)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {

        AndroidView(
            modifier = Modifier.size(1.dp),
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(1, 1)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Safari/537.36"
                    addJavascriptInterface(bridge, "SaudiaBridge")
                    webChromeClient = object : WebChromeClient() {}

                    if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                        WebViewCompat.addDocumentStartJavaScript(
                            this, HOOK_JS,
                            setOf("https://www.saudia.com", "https://saudia.com")
                        )
                    }

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(v: WebView?, url: String?, fav: Bitmap?) {
                            super.onPageStarted(v, url, fav)
                            v?.evaluateJavascript(HOOK_JS, null)
                        }
                        override fun onPageFinished(v: WebView?, url: String?) {
                            super.onPageFinished(v, url)
                            v?.evaluateJavascript(HOOK_JS, null)
                        }
                    }
                    loadUrl("https://www.saudia.com/ar-SA")
                }
            }
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = if (jwtReady) Color(0xFF00C853) else Color(0xFF2196F3)),
            modifier = Modifier.padding(24.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (!jwtReady) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                    Spacer(modifier = Modifier.height(12.dp))
                }
                Text(status, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(modifier = Modifier.height(16.dp))
                TextButton(onClick = onCancel) {
                    Text("إلغاء", color = Color.White.copy(alpha = 0.8f))
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
            try { return sessionStorage.getItem('saudia-session-id') || localStorage.getItem('saudia-session-id') || ''; }
            catch(e) { return ''; }
        }

        function captureToken(token) {
            var sid = getSid();
            SaudiaBridge.onJwt(token, sid);
            SaudiaBridge.onLog('CAPTURED JWT sid=' + sid);
        }

        var origFetch = window.fetch;
        window.fetch = function(input, init) {
            var url = typeof input === 'string' ? input : (input && input.url) || '';
            var isTok = url.indexOf('/token/init') > -1 || url.indexOf('/token/initialization') > -1;
            if (isTok) SaudiaBridge.onLog('FETCH: ' + url);
            var p = origFetch.apply(this, arguments);
            if (isTok) {
                p.then(function(r) {
                    r.clone().text().then(function(t) {
                        SaudiaBridge.onLog('RESP: ' + t.substring(0, 100));
                        try { var d = JSON.parse(t); if (d.access_token) captureToken('Bearer ' + d.access_token); } catch(e) {}
                    });
                });
            }
            return p;
        };

        setInterval(function() {
            var keys = ['saudia-access-token', 'access_token', 'authorization'];
            for (var i = 0; i < keys.length; i++) {
                var v = sessionStorage.getItem(keys[i]) || localStorage.getItem(keys[i]);
                if (v && v.indexOf('eyJ') > -1) {
                    captureToken(v.indexOf('Bearer ') === 0 ? v : 'Bearer ' + v);
                    return;
                }
            }
        }, 1000);
    })();
"""
