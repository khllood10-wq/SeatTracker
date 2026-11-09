package com.example.saudiaseattracker

import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.CompletableDeferred
import java.util.UUID

object WebViewManager {
    private var webView: WebView? = null
    private val pendingFetches = mutableMapOf<String, CompletableDeferred<String>>()

    // استخدمها في LoginScreen بدل ما تعمل WebView جديد
    fun attach(existingWebView: WebView) {
        if (webView == null) {
            webView = existingWebView
            webView?.addJavascriptInterface(Bridge, "AndroidBridge")
        }
    }

    suspend fun encryptedFetch(url: String, method: String, bodyJson: String?): String {
        val wv = webView ?: return "ERR|WebView not attached"
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<String>()
        pendingFetches[id] = deferred

        val hasBody = bodyJson != null && bodyJson != "null"
        val sendLine = if (hasBody) "xhr.send($bodyJson);" else "xhr.send();"

        val js = """
            (function(){
                try {
                    var xhr = new XMLHttpRequest();
                    xhr.open('$method', '$url', true);
                    xhr.setRequestHeader('Content-Type', 'application/json');
                    xhr.setRequestHeader('Accept', 'application/json');
                    xhr.onreadystatechange = function() {
                        if (xhr.readyState === 4) {
                            AndroidBridge.onFetchResult('$id', xhr.status + '|' + xhr.responseText);
                        }
                    };
                    $sendLine
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
