package com.example.saudiaseattracker

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

data class CapturedData(
    val jwt: String,
    val cookie: String,
    val xdToken: String,
    val saaSessionId: String
)

@SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
class HiddenWebViewManager(
    private val activity: Activity,
    private val reese84Cookie: String,
    private val userAgent: String
) {
    private var webView: WebView? = null
    @Volatile private var capturedCookie: String? = null
    @Volatile private var capturedXdToken: String? = null
    @Volatile private var capturedSaaId: String? = null

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
                    fun onTokenReady(jwt: String) {
                        Handler(Looper.getMainLooper()).post {
                            val cookie = capturedCookie
                            val xdToken = capturedXdToken
                            val saaId = capturedSaaId

                            if (cookie.isNullOrBlank() || xdToken.isNullOrBlank() || saaId.isNullOrBlank()) {
                                cleanup()
                                onError("لم يتم التقاط كل الهيدرات من WebView (cookie=${!cookie.isNullOrBlank()}, xdToken=${!xdToken.isNullOrBlank()}, saaId=${!saaId.isNullOrBlank()})")
                                return@post
                            }

                            cleanup()
                            val cleanToken = if (jwt.startsWith("Bearer ")) jwt else "Bearer $jwt"
                            onSuccess(CapturedData(cleanToken, cookie, xdToken, saaId))
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
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        try {
                            val host = request?.url?.host ?: return null
                            if (host.contains("dapi.saudia.com") || host.contains("saudia.com")) {
                                val headers = request.requestHeaders
                                val cookie = headers["Cookie"] ?: headers["cookie"]
                                val xd = headers["x-d-token"]
                                val sid = headers["saa-session-id"]
                                if (!cookie.isNullOrBlank() && !xd.isNullOrBlank() && !sid.isNullOrBlank()) {
                                    capturedCookie = cookie
                                    capturedXdToken = xd
                                    capturedSaaId = sid
                                }
                            }
                        } catch (e: Exception) {}
                        return null
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        val js = """
                            (function() {
                                if (window.__fetchAllStarted) return;
                                window.__fetchAllStarted = true;
                                
                                function findJwt() {
                                    try {
                                        return sessionStorage.getItem('saudia-access-token') || 
                                               localStorage.getItem('saudia-access-token') ||
                                               localStorage.getItem('rawAirData');
                                    } catch(e) { return null; }
                                }
                                
                                function checkAndFetch() {
                                    var jwt = findJwt();
                                    if (jwt && jwt.indexOf('eyJ') > -1) {
                                        // جلب headers حقيقية عبر طلب GET صغير
                                        fetch('https://dapi.saudia.com/shopping/services/by-cart?cartId=test&guestOfficeId=JEDSV08MA&lang=ar-SA', {
                                            method: 'GET'
                                        }).then(function() {
                                            setTimeout(function() {
                                                window.AndroidBridge.onTokenReady(jwt);
                                            }, 1000);
                                        }).catch(function(e) {
                                            window.AndroidBridge.onError('fetch failed: ' + e.message);
                                        });
                                        return true;
                                    }
                                    return false;
                                }
                                
                                var interval = setInterval(function() {
                                    if (checkAndFetch()) clearInterval(interval);
                                }, 500);
                                
                                setTimeout(function() {
                                    clearInterval(interval);
                                    window.AndroidBridge.onError('Timeout');
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
