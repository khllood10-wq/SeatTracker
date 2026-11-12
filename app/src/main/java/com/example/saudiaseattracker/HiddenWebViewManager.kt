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

@SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
class HiddenWebViewManager(
    private val activity: Activity,
    private val reese84Cookie: String,
    private val userAgent: String
) {
    private var webView: WebView? = null

    fun fetchToken(onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        Handler(Looper.getMainLooper()).post {
            try {
                // 1. إنشاء WebView وهمي بحجم 1x1 بكسل، شفاف، ومخفي خلف الواجهة
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

                // 2. حقن كوكي reese84 من Hyper
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(webView, true)
                cookieManager.setCookie("https://www.saudia.com", reese84Cookie)
                cookieManager.setCookie("https://dapi.saudia.com", reese84Cookie)
                cookieManager.flush()

                // 3. إضافة الجسر لاستقبال التوكن
                webView?.addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onTokenFound(token: String) {
                        Handler(Looper.getMainLooper()).post {
                            cleanup()
                            val cleanToken = if (token.startsWith("Bearer ")) token else "Bearer $token"
                            onSuccess(cleanToken)
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

                // 4. اعتراض الطلبات لاكتشاف التوكن
                webView?.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        val js = """
                            (function() {
                                if (window.tokenPollingStarted) return;
                                window.tokenPollingStarted = true;
                                
                                function checkToken() {
                                    try {
                                        var t = sessionStorage.getItem('saudia-access-token') || 
                                                localStorage.getItem('saudia-access-token') ||
                                                localStorage.getItem('rawAirData');
                                        if (t && t.indexOf('eyJ') > -1) {
                                            window.AndroidBridge.onTokenFound(t);
                                            return true;
                                        }
                                    } catch(e) {}
                                    return false;
                                }
                                
                                var interval = setInterval(function() {
                                    if (checkToken()) {
                                        clearInterval(interval);
                                    }
                                }, 500);

                                setTimeout(function() {
                                    clearInterval(interval);
                                    window.AndroidBridge.onError("Timeout: Token not found in 10s");
                                }, 15000);
                            })();
                        """.trimIndent()
                        view?.evaluateJavascript(js, null)
                    }
                }

                // 5. إضافة الـ WebView إلى واجهة الـ Activity (بدون صلاحيات إضافية)
                val rootView = activity.window.decorView as ViewGroup
                rootView.addView(webView)

                // 6. تحميل الصفحة
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
