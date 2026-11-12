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
    val sessionId: String
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
                    fun onTokensFound(token: String, sessionId: String) {
                        Handler(Looper.getMainLooper()).post {
                            val cookiesWww = CookieManager.getInstance().getCookie("https://www.saudia.com") ?: ""
                            val cookiesDapi = CookieManager.getInstance().getCookie("https://dapi.saudia.com") ?: ""
                            val cookieMap = mutableMapOf<String, String>()
                            listOf(cookiesWww, cookiesDapi).forEach { str ->
                                str.split(";").forEach { part ->
                                    val t = part.trim()
                                    if (t.isNotEmpty()) {
                                        val eq = t.indexOf('=')
                                        if (eq > 0) cookieMap[t.substring(0, eq)] = t.substring(eq + 1)
                                    }
                                }
                            }
                            val combined = cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
                            cleanup()
                            val cleanToken = if (token.startsWith("Bearer ")) token else "Bearer $token"
                            onSuccess(WebViewResult(cleanToken, combined, sessionId))
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
                        // 👇 نحقن سكربت اعتراض fetch قبل تحميل الصفحة
                        view?.evaluateJavascript(HOOK_JS, null)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        view?.evaluateJavascript(HOOK_JS, null)
                        
                        // بدء البحث عن التوكن
                        val js = """
                            (function() {
                                if (window.tokenPollingStarted) return;
                                window.tokenPollingStarted = true;
                                function check() {
                                    try {
                                        var jwt = sessionStorage.getItem('saudia-access-token') || 
                                                  localStorage.getItem('saudia-access-token') ||
                                                  localStorage.getItem('rawAirData');
                                        var sid = window.__capturedSessionId || '';
                                        if (jwt && jwt.indexOf('eyJ') > -1) {
                                            window.AndroidBridge.onTokensFound(jwt, sid);
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

    companion object {
        private const val HOOK_JS = """
            (function() {
                if (window.__sessionHooked) return;
                window.__sessionHooked = true;
                
                // اعتراض fetch
                var origFetch = window.fetch;
                window.fetch = function(input, init) {
                    var url = typeof input === 'string' ? input : (input && input.url) || '';
                    var p = origFetch.apply(this, arguments);
                    if (url.indexOf('token/initialization') > -1) {
                        p.then(function(r) {
                            r.clone().json().then(function(data) {
                                if (data.session_id) {
                                    window.__capturedSessionId = data.session_id;
                                }
                            }).catch(function(){});
                        }).catch(function(){});
                    }
                    return p;
                };
                
                // اعتراض XHR
                var origOpen = XMLHttpRequest.prototype.open;
                var origSend = XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.open = function(m, u) {
                    this.__url = u;
                    return origOpen.apply(this, arguments);
                };
                XMLHttpRequest.prototype.send = function() {
                    var self = this;
                    if (self.__url && self.__url.indexOf('token/initialization') > -1) {
                        self.addEventListener('load', function() {
                            try {
                                var d = JSON.parse(self.responseText);
                                if (d.session_id) window.__capturedSessionId = d.session_id;
                            } catch(e) {}
                        });
                    }
                    return origSend.apply(this, arguments);
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
