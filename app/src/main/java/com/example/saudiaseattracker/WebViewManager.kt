package com.example.saudiaseattracker

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import java.util.UUID

object WebViewManager {
    private var webView: WebView? = null
    private val pendingFetches = mutableMapOf<String, CompletableDeferred<String>>()

    var capturedAuth: String? = null; private set
    var capturedXD: String? = null; private set
    var capturedSession: String? = null; private set

    @SuppressLint("SetJavaScriptEnabled")
    fun init(context: Context) {
        if (webView != null) return
        webView = WebView(context).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(0, 0)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    if (request?.url?.toString()?.contains("dapi.saudia.com") == true) {
                        val h = request.requestHeaders
                        h["Authorization"]?.let { if (it.startsWith("Bearer ") && capturedAuth == null) capturedAuth = it }
                        h["x-d-token"]?.let { if (capturedXD == null) capturedXD = it }
                        h["saa-session-id"]?.let { if (capturedSession == null) capturedSession = it }
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }
            addJavascriptInterface(Bridge, "AndroidBridge")
            loadUrl("https://www.saudia.com/ar-SA/booking/passengerDetails")
        }
    }

    fun isReady(): Boolean = capturedAuth != null && capturedXD != null

    fun getCookies(): String {
        val cm = CookieManager.getInstance()
        return listOf("https://www.saudia.com", "https://dapi.saudia.com")
            .mapNotNull { cm.getCookie(it) }
            .joinToString("; ")
    }

    fun reset() {
        webView?.destroy()
        webView = null
        capturedAuth = null
        capturedXD = null
        capturedSession = null
        pendingFetches.clear()
    }

    suspend fun encryptedFetch(url: String, method: String, bodyJson: String): String {
        val wv = webView ?: return "ERROR: WebView not initialized"
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<String>()
        pendingFetches[id] = deferred

        val js = """
            (function(){
                try {
                    var bodyObj = $bodyJson;
                    var xhr = new XMLHttpRequest();
                    xhr.open('$method', '$url', true);
                    xhr.setRequestHeader('Content-Type', 'application/json');
                    xhr.setRequestHeader('Accept', 'application/json');
                    xhr.onreadystatechange = function() {
                        if (xhr.readyState === 4) {
                            AndroidBridge.onFetchResult('$id', xhr.status + '|' + xhr.responseText);
                        }
                    };
                    xhr.send(JSON.stringify(bodyObj));
                } catch(e) {
                    AndroidBridge.onFetchResult('$id', 'ERR|' + e.message);
                }
            })();
        """.trimIndent()

        wv.post { wv.evaluateJavascript(js, null) }
        return deferred.await()
    }

    private object Bridge {
        @JavascriptInterface
        fun onFetchResult(id: String, result: String) {
            pendingFetches[id]?.complete(result)
            pendingFetches.remove(id)
        }
    }
}
