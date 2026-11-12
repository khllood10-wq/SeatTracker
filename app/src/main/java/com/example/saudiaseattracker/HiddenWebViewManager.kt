package com.example.saudiaseattracker

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

@SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
class HiddenWebViewManager(
    private val context: Context,
    private val reese84Cookie: String,
    private val userAgent: String
) {
    private var webView: WebView? = null
    private var windowManager: WindowManager? = null
    private var dummyView: FrameLayout? = null

    fun fetchToken(onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        Handler(Looper.getMainLooper()).post {
            try {
                // 1. إنشاء WebView وهمي بحجم 1x1
                webView = WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(1, 1)
                    alpha = 0f // شفاف تماماً
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.userAgentString = userAgent
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    
                    // إخفاء علامات WebView
                    settings.setSupportMultipleWindows(false)
                    settings.javaScriptCanOpenWindowsAutomatically = false
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
                        // حقن سكربت لمراقبة الذاكرة واستخراج التوكن
                        val js = """
                            (function() {
                                if (window.tokenPollingStarted) return;
                                window.tokenPollingStarted = true;
                                
                                function checkToken() {
                                    try {
                                        var t = sessionStorage.getItem('saudia-access-token') || 
                                                localStorage.getItem('saudia-access-token') ||
                                                sessionStorage.getItem('rawAirData');
                                        if (t && t.indexOf('eyJ') > -1) {
                                            window.AndroidBridge.onTokenFound(t);
                                            return true;
                                        }
                                    } catch(e) {}
                                    return false;
                                }
                                
                                // فحص سريع كل 500 مللي ثانية
                                var interval = setInterval(function() {
                                    if (checkToken()) {
                                        clearInterval(interval);
                                    }
                                }, 500);

                                // مهلة قصوى 10 ثوان
                                setTimeout(function() {
                                    clearInterval(interval);
                                    window.AndroidBridge.onError("Timeout: Token not found in 10s");
                                }, 10000);
                            })();
                        """.trimIndent()
                        view?.evaluateJavascript(js, null)
                    }
                }

                // 5. إضافة الـ WebView إلى نافذة شفافة (ليعمل في الخلفية)
                windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                dummyView = FrameLayout(context).apply {
                    addView(webView)
                }
                
                val params = WindowManager.LayoutParams(
                    1, 1,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    android.graphics.PixelFormat.TRANSLUCENT
                )
                params.x = -10
                params.y = -10
                
                windowManager?.addView(dummyView, params)

                // 6. بدء تحميل الصفحة
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
            webView = null
            dummyView?.let { view ->
                try { windowManager?.removeView(view) } catch (e: Exception) {}
            }
            dummyView = null
        } catch (e: Exception) {}
    }
}
