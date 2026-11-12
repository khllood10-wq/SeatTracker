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

data class CapturedData(
    val jwt: String,
    val cookie: String,
    val saaSessionId: String,
    val bid: String,
    val docCookies: String
)

@SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
class HiddenWebViewManager(
    private val activity: Activity,
    private val reese84Cookie: String,
    private val userAgent: String
) {
    private var webView: WebView? = null

    fun fetchAll(onSuccess: (CapturedData) -> Unit, onError: (String) -> Unit) {
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
                    fun onCaptured(jwt: String, saaId: String, bid: String, docCookies: String) {
                        Handler(Looper.getMainLooper()).postDelayed({
                            try {
                                val c1 = CookieManager.getInstance().getCookie("https://www.saudia.com") ?: ""
                                val c2 = CookieManager.getInstance().getCookie("https://dapi.saudia.com") ?: ""
                                val c3 = CookieManager.getInstance().getCookie("https://saudia.com") ?: ""

                                val map = mutableMapOf<String, String>()
                                // نجمع كل الكوكيز من CookieManager
                                listOf(c1, c2, c3).forEach { str ->
                                    str.split(";").forEach { part ->
                                        val t = part.trim()
                                        if (t.isNotEmpty()) {
                                            val eq = t.indexOf('=')
                                            if (eq > 0) map[t.substring(0, eq).trim()] = t.substring(eq + 1).trim()
                                        }
                                    }
                                }
                                // نضيف document.cookie
                                docCookies.split(";").forEach { part ->
                                    val t = part.trim()
                                    if (t.isNotEmpty()) {
                                        val eq = t.indexOf('=')
                                        if (eq > 0) map[t.substring(0, eq).trim()] = t.substring(eq + 1).trim()
                                    }
                                }
                                val combined = map.entries.joinToString("; ") { "${it.key}=${it.value}" }

                                cleanup()
                                val cleanToken = if (jwt.startsWith("Bearer ")) jwt else "Bearer $jwt"
                                onSuccess(CapturedData(cleanToken, combined, saaId, bid, docCookies))
                            } catch (e: Exception) {
                                cleanup()
                                onError("Error collecting: ${e.message}")
                            }
                        }, 3000)
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
                                if (window.__pollerStarted) return;
                                window.__pollerStarted = true;
                                
                                function check() {
                                    try {
                                        var jwt = sessionStorage.getItem('saudia-access-token') || 
                                                  localStorage.getItem('saudia-access-token') ||
                                                  localStorage.getItem('rawAirData');
                                        if (jwt && jwt.indexOf('eyJ') > -1) {
                                            setTimeout(function() {
                                                var sid = window.__capturedSaaId || '';
                                                var bid = window.__capturedBid || '';
                                                var docCookies = document.cookie || '';
                                                window.AndroidBridge.onCaptured(jwt, sid, bid, docCookies);
                                            }, 4000);
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
                                }, 30000);
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

    companion object {
        private val HOOK_JS = """
            (function() {
                if (window.__hooked) return;
                window.__hooked = true;
                window.__capturedSaaId = '';
                window.__capturedBid = '';
                
                var origSetHeader = XMLHttpRequest.prototype.setRequestHeader;
                XMLHttpRequest.prototype.setRequestHeader = function(name, value) {
                    try {
                        var n = (name || '').toLowerCase();
                        if (n === 'saa-session-id') window.__capturedSaaId = value;
                        if (n === 'browserid') window.__capturedBid = value;
                    } catch(e) {}
                    return origSetHeader.apply(this, arguments);
                };
                
                var origFetch = window.fetch;
                window.fetch = function(input, init) {
                    try {
                        if (init && init.headers) {
                            var h = init.headers;
                            if (h instanceof Headers) {
                                var v = h.get('saa-session-id');
                                if (v) window.__capturedSaaId = v;
                                var b = h.get('browserid');
                                if (b) window.__capturedBid = b;
                            } else if (Array.isArray(h)) {
                                for (var i = 0; i < h.length; i++) {
                                    var k = (h[i][0] || '').toLowerCase();
                                    if (k === 'saa-session-id') window.__capturedSaaId = h[i][1];
                                    if (k === 'browserid') window.__capturedBid = h[i][1];
                                }
                            } else if (typeof h === 'object') {
                                for (var key in h) {
                                    var kk = key.toLowerCase();
                                    if (kk === 'saa-session-id') window.__capturedSaaId = h[key];
                                    if (kk === 'browserid') window.__capturedBid = h[key];
                                }
                            }
                        }
                    } catch(e) {}
                    return origFetch.apply(this, arguments);
                };
                
                var origOpen = XMLHttpRequest.prototype.open;
                XMLHttpRequest.prototype.open = function(method, url, async, user, password) {
                    this.__url = url;
                    return origOpen.apply(this, arguments);
                };
            })();
        """.trimIndent()
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
}
