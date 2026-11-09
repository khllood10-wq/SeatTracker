package com.example.saudiaseattracker

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.CompletableDeferred
import java.util.UUID

object WebViewManager {
    private var webView: WebView? = null
    private val pendingRequests = mutableMapOf<String, CompletableDeferred<String>>()
    var isInitialized = false

    @SuppressLint("SetJavaScriptEnabled")
    fun init(context: Context) {
        if (webView != null) return
        webView = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            addJavascriptInterface(Bridge, "AndroidBridge")
            loadUrl("https://www.saudia.com/ar-SA/booking/passengerDetails")
        }
        isInitialized = true
    }

    suspend fun submitEncryptedPassenger(cartId: String, travelerId: String, plainJson: String): String {
        val wv = webView ?: return "ERROR: WebView not initialized"
        val requestId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<String>()
        pendingRequests[requestId] = deferred

        // نستخدم fetch من داخل WebView — الموقع سيقوم بتشفير البيانات تلقائياً
        val js = """
            (function() {
                try {
                    const payload = $plainJson;
                    fetch('https://dapi.saudia.com/shopping/carts/$cartId/travelers/web/$travelerId', {
                        method: 'PATCH',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify(payload)
                    })
                    .then(res => res.text())
                    .then(text => AndroidBridge.onResult('$requestId', text))
                    .catch(err => AndroidBridge.onResult('$requestId', 'ERROR: ' + err.message));
                } catch(e) {
                    AndroidBridge.onResult('$requestId', 'ERROR: ' + e.message);
                }
            })();
        """.trimIndent()

        wv.post { wv.evaluateJavascript(js, null) }
        return deferred.await()
    }

    private object Bridge {
        @JavascriptInterface
        fun onResult(requestId: String, result: String) {
            pendingRequests[requestId]?.complete(result)
            pendingRequests.remove(requestId)
        }
    }
}
