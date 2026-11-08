package com.example.saudiaseattracker

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
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

    // ⚠️ Windows UA — Imperva يرفض Android UA
    val WINDOWS_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.size(32.dp))

        AndroidView(
            modifier = Modifier.size(1.dp),
            factory = { context ->
                val webView = WebView(context)
                webView.layoutParams = ViewGroup.LayoutParams(1, 1)

                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    // ⚠️ فرض Windows UA قبل التحميل
                    userAgentString = WINDOWS_UA
                }

                webView.webChromeClient = WebChromeClient()

                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    setAcceptThirdPartyCookies(webView, true)
                }

                webView.webViewClient = object : WebViewClient() {

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: android.webkit.WebResourceRequest?
                    ): android.webkit.WebResourceResponse? {
                        val url = request?.url.toString()

                        if (!hasIntercepted && url.contains("dapi.saudia.com")) {
                            val headers = request?.requestHeaders ?: emptyMap()

                            val authHeader = headers.entries
                                .firstOrNull { it.key.equals("Authorization", true) }?.value
                            val xdToken = headers.entries
                                .firstOrNull { it.key.equals("x-d-token", true) }?.value
                            val sessionId = headers.entries
                                .firstOrNull { it.key.equals("saa-session-id", true) }?.value

                            if (!authHeader.isNullOrBlank() && authHeader.startsWith("Bearer ")) {
                                val finalSessionId = sessionId ?: java.util.UUID.randomUUID().toString()

                                // ✅ نحفظ Windows UA (ليس UA الـ WebView)
                                viewModel.saveTokens(
                                    authHeader,
                                    xdToken ?: "",
                                    finalSessionId,
                                    WINDOWS_UA
                                )
                                hasIntercepted = true

                                view?.post {
                                    navController.navigate(Screen.Search.route) {
                                        // ✅ احذف Search (إن وُجد) لتجنب تراكم الـ backstack
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
