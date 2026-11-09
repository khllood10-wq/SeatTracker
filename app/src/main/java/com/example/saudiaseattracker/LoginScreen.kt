package com.example.saudiaseattracker

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(navController: NavController, viewModel: FlightViewModel) {
    var hasIntercepted by remember { mutableStateOf(false) }
    var loadAttempts by remember { mutableStateOf(0) }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.size(32.dp))

        AndroidView(
            modifier = Modifier.size(1.dp),
            factory = { context ->
                val webView = WebView(context)
                val capturedUserAgent = webView.settings.userAgentString

                webView.layoutParams = ViewGroup.LayoutParams(1, 1)

                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }

                webView.webChromeClient = WebChromeClient()

                android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

                // ═══ 🔥 السطر الجديد: اربط الـ WebView بـ WebViewManager ═══
                WebViewManager.attach(webView)

                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: android.webkit.WebResourceRequest?
                    ): android.webkit.WebResourceResponse? {
                        val url = request?.url.toString()

                        if (!hasIntercepted && url.contains("dapi.saudia.com")) {
                            val authHeader = request?.requestHeaders?.get("Authorization")
                            val xdToken = request?.requestHeaders?.get("x-d-token")
                            val sessionId = request?.requestHeaders?.get("saa-session-id")

                            if (authHeader != null && authHeader.startsWith("Bearer ") && authHeader.length > 20) {
                                val finalSessionId = sessionId ?: java.util.UUID.randomUUID().toString()
                                viewModel.saveTokens(authHeader, xdToken ?: "", finalSessionId, capturedUserAgent)
                                hasIntercepted = true
                                view?.post {
                                    navController.navigate(Screen.Search.route) {
                                        popUpTo(Screen.Search.route) { inclusive = true }
                                    }
                                }
                            }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (!hasIntercepted && loadAttempts < 3) {
                            loadAttempts++
                            view?.postDelayed({
                                if (!hasIntercepted) view.reload()
                            }, 2000)
                        }
                    }
                }

                webView.loadUrl("https://www.saudia.com/ar-SA")
                webView
            }
        )
    }
}
