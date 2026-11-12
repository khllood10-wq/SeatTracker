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
                    fun onTokensFound(token: String, saaSessionId: String) {
                        Handler(Looper.getMainLooper()).post {
                            // نسحب الكوكيز من النطاقين
                            val cookiesWww = CookieManager.getInstance().getCookie("https://www.saudia.com") ?: ""
                            val cookiesDapi = CookieManager.getInstance().getCookie("https://dapi.saudia.com") ?: ""
                            val combined = mergeCookies(cookiesWww, cookiesDapi)
                            
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
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        val js = """
                            (function() {
                                if (window.tokenPollingStarted) return;
                                window.tokenPollingStarted = true;
                                
                                function checkTokens() {
                                    try {
                                        var jwt = sessionStorage.getItem('saudia-access-token') || 
                                                  localStorage.getItem('saudia-access-token') ||
                                                  localStorage.getItem('rawAirData');
                                        
                                        // البحث عن saa-session-id في كل الأماكن
                                        var saaSessionId = 
                                            sessionStorage.getItem('saa-session-id') || 
                                            sessionStorage.getItem('saaSessionId') ||
                                            sessionStorage.getItem('saa_session_id') ||
                                            localStorage.getItem('saa-session-id') || 
                                            localStorage.getItem('saaSessionId') ||
                                            localStorage.getItem('saa_session_id') ||
                                            '';
                                        
                                        // إذا لم نجده، ابحث في كل مفاتيح sessionStorage
                                        if (!saaSessionId) {
                                            for (var i = 0; i < sessionStorage.length; i++) {
                                                var k = sessionStorage.key(i);
                                                if (k && k.toLowerCase().indexOf('saa') > -1 && k.toLowerCase().indexOf('session') > -1) {
                                                    saaSessionId = sessionStorage.getItem(k) || '';
                                                    if (saaSessionId) break;
                                                }
                                            }
                                        }
                                        
                                        // إذا لم نجده، ابحث في كل مفاتيح localStorage
                                        if (!saaSessionId) {
                                            for (var j = 0; j < localStorage.length; j++) {
                                                var k2 = localStorage.key(j);
                                                if (k2 && k2.toLowerCase().indexOf('saa') > -1 && k2.toLowerCase().indexOf('session') > -1) {
                                                    saaSessionId = localStorage.getItem(k2) || '';
                                                    if (saaSessionId) break;
                                                }
                                            }
                                        }
                                        
                                        if (jwt && jwt.indexOf('eyJ') > -1) {
                                            window.AndroidBridge.onTokensFound(jwt, saaSessionId);
                                            return true;
                                        }
                                    } catch(e) {}
                                    return false;
                                }
                                
                                var interval = setInterval(function() {
                                    if (checkTokens()) clearInterval(interval);
                                }, 500);
                                
                                setTimeout(function() {
                                    clearInterval(interval);
                                    window.AndroidBridge.onError("Timeout: Token not found in 25s");
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

    private fun mergeCookies(c1: String, c2: String): String {
        val map = mutableMapOf<String, String>()
        for (cookieStr in listOf(c1, c2)) {
            cookieStr.split(";").forEach { part ->
                val trimmed = part.trim()
                if (trimmed.isNotEmpty()) {
                    val eq = trimmed.indexOf('=')
                    if (eq > 0) {
                        val name = trimmed.substring(0, eq).trim()
                        val value = trimmed.substring(eq + 1).trim()
                        map[name] = value
                    }
                }
            }
        }
        return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
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
