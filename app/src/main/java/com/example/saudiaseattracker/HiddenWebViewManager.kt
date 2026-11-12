package com.example.saudiaseattracker

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

data class WebViewResult(
    val token: String,
    val cookie: String,
    val saaSessionId: String
)

@SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
class HiddenWebViewManager(
    private val activity: Activity,
    private val reese84Cookie: String,
    private val userAgent: String
) {
    private var webView: WebView? = null

    fun fetchToken(onSuccess: (WebViewResult) -> Unit, onError: (String) -> Unit) {
        Handler(Looper.getMainLooper()).post {
            try {
                webView = WebView(activity).apply {
                    layoutParams = FrameLayout.LayoutParams(1, 1)
                    alpha = 0f
                    setBackgroundColor(Color.TRANSPARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.userAgentString = userAgent
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                }

                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(webView, true)
                cookieManager.setCookie("https://www.saudia.com", reese84Cookie)
                cookieManager.setCookie("https://dapi.saudia.com", reese84Cookie)
                cookieManager.flush()

                webView?.addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onTokenFound(token: String, saaSessionId: String) {
                        Handler(Looper.getMainLooper()).post {
                            val cookiesWww = CookieManager.getInstance().getCookie("https://www.saudia.com") ?: ""
                            val cookiesDapi = CookieManager.getInstance().getCookie("https://dapi.saudia.com") ?: ""
                            val map = mutableMapOf<String, String>()
                            listOf(cookiesWww, cookiesDapi).forEach { str ->
                                str.split(";").forEach { part ->
                                    val t = part.trim()
                                    if (t.isNotEmpty()) {
                                        val eq = t.indexOf('=')
                                        if (eq > 0) map[t.substring(0, eq)] = t.substring(eq + 1)
                                    }
                                }
                            }
                            val combined = map.entries.joinToString("; ") { "${it.key}=${it.value}" }
                            cleanup()
                            val cleanToken = if (token.startsWith("Bearer ")) token else "Bearer $token"
                            onSuccess(WebViewResult(cleanToken, combined, saaSessionId))
                        }
                    }

                    @JavascriptInterface
                    fun onError(msg: String) {
                        Handler(Looper.getMainLooper()).post {
                            cleanup()
                            onError(msg)
                        }
                    }
                }, "AndroidBridge")

                webView?.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        view?.evaluateJavascript(HOOK_JS, null)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        view?.evaluateJavascript(HOOK_JS, null)
                        val js = """
                            (function() {
                                if (window.__tokenPollerStarted) return;
                                window.__tokenPollerStarted = true;
                                
                                // دالة شاملة للبحث عن saa-session-id
                                function findSaaSessionId() {
                                    if (window.__capturedSessionId) return window.__capturedSessionId;
                                    
                                    // 1. Storage
                                    try {
                                        for (var i = 0; i < localStorage.length; i++) {
                                            var k = localStorage.key(i);
                                            var v = localStorage.getItem(k);
                                            if (v && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(v)) {
                                                if (k.toLowerCase().indexOf('saa') > -1 || k.toLowerCase().indexOf('session') > -1 || k.toLowerCase().indexOf('sid') > -1) {
                                                    return v;
                                                }
                                            }
                                        }
                                        for (var j = 0; j < sessionStorage.length; j++) {
                                            var k2 = sessionStorage.key(j);
                                            var v2 = sessionStorage.getItem(k2);
                                            if (v2 && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(v2)) {
                                                if (k2.toLowerCase().indexOf('saa') > -1 || k2.toLowerCase().indexOf('session') > -1 || k2.toLowerCase().indexOf('sid') > -1) {
                                                    return v2;
                                                }
                                            }
                                        }
                                    } catch(e) {}
                                    
                                    // 2. Window object
                                    var candidates = ['saaSessionId', 'saa_session_id', 'sessionId', 'session_id', 'sid'];
                                    for (var k = 0; k < candidates.length; k++) {
                                        if (window[candidates[k]]) return window[candidates[k]];
                                    }
                                    
                                    return '';
                                }
                                
                                function check() {
                                    try {
                                        var jwt = sessionStorage.getItem('saudia-access-token') || 
                                                  localStorage.getItem('saudia-access-token') ||
                                                  localStorage.getItem('rawAirData');
                                        if (jwt && jwt.indexOf('eyJ') > -1) {
                                            // انتظر 3 ثواني إضافية لعل الموقع يجري طلبات API إضافية
                                            setTimeout(function() {
                                                var sid = findSaaSessionId();
                                                window.AndroidBridge.onTokenFound(jwt, sid);
                                            }, 3000);
                                            return true;
                                        }
                                    } catch(e) {}
                                    return false;
                                }
                                var interval = setInterval(function() {
                                    if (check()) clearInterval(interval);
                                }, 500);
                                setTimeout(function() {
                                    clearInterval(interval);
                                    window.AndroidBridge.onError("Timeout");
                                }, 25000);
                            })();
                        """.trimIndent()
                        view?.evaluateJavascript(js, null)
                    }
                }

                val rootView = activity.window.decorView as ViewGroup
                rootView.addView(webView)
                webView?.loadUrl("https://www.saudia.com/ar-SA")

            } catch (e: Exception) {
                cleanup()
                onError(e.message ?: "Unknown error")
            }
        }
    }

    private fun cleanup() {
        try {
            webView?.stopLoading()
            webView?.loadUrl("about:blank")
            webView?.destroy()
            val parent = webView?.parent as? ViewGroup
            parent?.removeView(webView)
            webView = null
        } catch (e: Exception) {}
    }

    companion object {
        private val HOOK_JS = """
            (function() {
                if (window.__hookInstalled) return;
                window.__hookInstalled = true;
                
                // 1. Hook setRequestHeader to capture saa-session-id
                var origSetHeader = XMLHttpRequest.prototype.setRequestHeader;
                XMLHttpRequest.prototype.setRequestHeader = function(name, value) {
                    try {
                        if (name && name.toLowerCase() === 'saa-session-id') {
                            window.__capturedSessionId = value;
                        }
                    } catch(e) {}
                    return origSetHeader.apply(this, arguments);
                };
                
                // 2. Hook fetch to capture saa-session-id from init.headers
                var origFetch = window.fetch;
                window.fetch = function(input, init) {
                    try {
                        if (init && init.headers) {
                            var h = init.headers;
                            if (h instanceof Headers) {
                                var v = h.get('saa-session-id') || h.get('Saa-Session-Id');
                                if (v) window.__capturedSessionId = v;
                            } else if (typeof h === 'object') {
                                for (var k in h) {
                                    if (k && k.toLowerCase() === 'saa-session-id') {
                                        window.__capturedSessionId = h[k];
                                    }
                                }
                            } else if (Array.isArray(h)) {
                                for (var i = 0; i < h.length; i++) {
                                    if (h[i][0] && h[i][0].toLowerCase() === 'saa-session-id') {
                                        window.__capturedSessionId = h[i][1];
                                    }
                                }
                            }
                        }
                    } catch(e) {}
                    return origFetch.apply(this, arguments);
                };
                
                // 3. Hook token/init response to look for session_id
                var origXhrOpen = XMLHttpRequest.prototype.open;
                var origXhrSend = XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.open = function(m, u) {
                    this.__url = u;
                    return origXhrOpen.apply(this, arguments);
                };
                XMLHttpRequest.prototype.send = function() {
                    var self = this;
                    if (self.__url && self.__url.indexOf('token/initialization') > -1) {
                        self.addEventListener('load', function() {
                            try {
                                var d = JSON.parse(self.responseText);
                                var keys = ['session_id', 'sessionId', 'saa_session_id', 'saaSessionId', 'sid'];
                                for (var i = 0; i < keys.length; i++) {
                                    if (d[keys[i]]) {
                                        window.__capturedSessionId = d[keys[i]];
                                        return;
                                    }
                                }
                                // Search all values for UUID pattern
                                for (var key in d) {
                                    if (typeof d[key] === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(d[key])) {
                                        window.__capturedSessionId = d[key];
                                        return;
                                    }
                                }
                            } catch(e) {}
                        });
                    }
                    return origXhrSend.apply(this, arguments);
                };
                
                // 4. Hook fetch responses of token/init
                var origFetch2 = window.fetch;
                window.fetch = function(input, init) {
                    var url = typeof input === 'string' ? input : (input && input.url) || '';
                    var p = origFetch2.apply(this, arguments);
                    if (url.indexOf('token/initialization') > -1) {
                        p.then(function(r) {
                            r.clone().text().then(function(text) {
                                try {
                                    var d = JSON.parse(text);
                                    var keys = ['session_id', 'sessionId', 'saa_session_id', 'saaSessionId', 'sid'];
                                    for (var i = 0; i < keys.length; i++) {
                                        if (d[keys[i]]) {
                                            window.__capturedSessionId = d[keys[i]];
                                            return;
                                        }
                                    }
                                    for (var key in d) {
                                        if (typeof d[key] === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(d[key])) {
                                            window.__capturedSessionId = d[key];
                                            return;
                                        }
                                    }
                                } catch(e) {}
                            });
                        }).catch(function(){});
                    }
                    return p;
                };
            })();
        """.trimIndent()
    }
}
