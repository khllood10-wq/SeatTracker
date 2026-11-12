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
    val saaSessionId: String
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
                    fun onCaptured(jwt: String, saaId: String) {
                        Handler(Looper.getMainLooper()).post {
                            // جمع الكوكيز من كل النطاقات
                            val c1 = CookieManager.getInstance().getCookie("https://www.saudia.com") ?: ""
                            val c2 = CookieManager.getInstance().getCookie("https://dapi.saudia.com") ?: ""
                            val c3 = CookieManager.getInstance().getCookie("https://saudia.com") ?: ""

                            val map = mutableMapOf<String, String>()
                            listOf(c1, c2, c3).forEach { str ->
                                str.split(";").forEach { part ->
                                    val t = part.trim()
                                    if (t.isNotEmpty()) {
                                        val eq = t.indexOf('=')
                                        if (eq > 0) map[t.substring(0, eq).trim()] = t.substring(eq + 1).trim()
                                    }
                                }
                            }
                            val combined = map.entries.joinToString("; ") { "${it.key}=${it.value}" }

                            cleanup()
                            val cleanToken = if (jwt.startsWith("Bearer ")) jwt else "Bearer $jwt"
                            onSuccess(CapturedData(cleanToken, combined, saaId))
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
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        val js = """
                            (function() {
                                if (window.__pollerStarted) return;
                                window.__pollerStarted = true;
                                
                                function findSaaId() {
                                    try {
                                        for (var i = 0; i < localStorage.length; i++) {
                                            var v = localStorage.getItem(localStorage.key(i));
                                            if (v && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(v)) {
                                                var k = localStorage.key(i).toLowerCase();
                                                if (k.indexOf('saa') > -1 || k.indexOf('session') > -1 || k.indexOf('sid') > -1) return v;
                                            }
                                        }
                                        for (var j = 0; j < sessionStorage.length; j++) {
                                            var v2 = sessionStorage.getItem(sessionStorage.key(j));
                                            if (v2 && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(v2)) {
                                                var k2 = sessionStorage.key(j).toLowerCase();
                                                if (k2.indexOf('saa') > -1 || k2.indexOf('session') > -1 || k2.indexOf('sid') > -1) return v2;
                                            }
                                        }
                                    } catch(e) {}
                                    return window.__capturedSessionId || '';
                                }
                                
                                function check() {
                                    try {
                                        var jwt = sessionStorage.getItem('saudia-access-token') || 
                                                  localStorage.getItem('saudia-access-token') ||
                                                  localStorage.getItem('rawAirData');
                                        if (jwt && jwt.indexOf('eyJ') > -1) {
                                            var sid = findSaaId();
                                            window.AndroidBridge.onCaptured(jwt, sid);
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
}
