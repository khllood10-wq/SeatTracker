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
    val saaSessionId: String,
    val documentCookies: String,
    val bidValue: String,
    val gaCid: String,
    val analyticsSessionId: String
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
                    fun onTokenFound(token: String, saaSessionId: String, docCookies: String) {
                        // 👇 ننتظر 5 ثوانٍ إضافية لجمع كل الكوكيز
                        Handler(Looper.getMainLooper()).postDelayed({
                            try {
                                // جمع الكوكيز من 3 نطاقات
                                val cookiesWww = CookieManager.getInstance().getCookie("https://www.saudia.com") ?: ""
                                val cookiesDapi = CookieManager.getInstance().getCookie("https://dapi.saudia.com") ?: ""
                                val cookiesRoot = CookieManager.getInstance().getCookie("https://saudia.com") ?: ""

                                // دمج كل الكوكيز (CookieManager + document.cookie)
                                val map = mutableMapOf<String, String>()
                                listOf(cookiesWww, cookiesDapi, cookiesRoot, docCookies).forEach { str ->
                                    str.split(";").forEach { part ->
                                        val t = part.trim()
                                        if (t.isNotEmpty()) {
                                            val eq = t.indexOf('=')
                                            if (eq > 0) map[t.substring(0, eq).trim()] = t.substring(eq + 1).trim()
                                        }
                                    }
                                }
                                val combined = map.entries.joinToString("; ") { "${it.key}=${it.value}" }

                                // استخراج القيم الحرجة
                                val bidEntry = map.entries.find { it.key.startsWith("bid_") }
                                val bidValue = bidEntry?.value ?: ""
                                val gaCookie = map["_ga"] ?: ""
                                val gaCid = if (gaCookie.isNotEmpty()) {
                                    val parts = gaCookie.split(".")
                                    if (parts.size >= 4) "${parts[2]}.${parts[3]}" else ""
                                } else ""
                                val gaSession = map["_ga_LVNZGXXWMB"] ?: ""
                                val analyticsSessionId = if (gaSession.startsWith("GS2.1.")) gaSession.removePrefix("GS2.1.") else ""

                                cleanup()
                                val cleanToken = if (token.startsWith("Bearer ")) token else "Bearer $token"
                                onSuccess(WebViewResult(
                                    token = cleanToken,
                                    cookie = combined,
                                    saaSessionId = saaSessionId,
                                    documentCookies = docCookies,
                                    bidValue = bidValue,
                                    gaCid = gaCid,
                                    analyticsSessionId = analyticsSessionId
                                ))
                            } catch (e: Exception) {
                                cleanup()
                                onError("Error collecting cookies: ${e.message}")
                            }
                        }, 5000) // 👈 انتظار 5 ثواني
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
                                
                                function findSaaSessionId() {
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
                                            // ننتظر 4 ثواني قبل الالتقاط (للكوكيز)
                                            setTimeout(function() {
                                                var sid = findSaaSessionId();
                                                var docCookies = document.cookie || '';
                                                window.AndroidBridge.onTokenFound(jwt, sid, docCookies);
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
        private const val HOOK_JS = """
            (function() {
                if (window.__hookInstalled) return;
                window.__hookInstalled = true;
                var origSetHeader = XMLHttpRequest.prototype.setRequestHeader;
                XMLHttpRequest.prototype.setRequestHeader = function(name, value) {
                    if (name && name.toLowerCase() === 'saa-session-id') window.__capturedSessionId = value;
                    return origSetHeader.apply(this, arguments);
                };
            })();
        """
    }
}
