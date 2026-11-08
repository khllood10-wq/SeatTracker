package com.example.saudiaseattracker

import android.annotation.SuppressLint
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

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text("جاري تجهيز الاتصال بالخطوط السعودية...")
        }

        AndroidView(
            modifier = Modifier.size(0.dp),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    
                    android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                    android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: android.webkit.WebResourceRequest?
                        ): android.webkit.WebResourceResponse? {
                            val url = request?.url.toString()
                            
                            // نوسع النطاق ليشمل www.saudia.com أيضاً
                            if (!hasIntercepted && url.contains("saudia.com")) {
                                val authHeader = request?.requestHeaders?.get("Authorization")
                                val xdToken = request?.requestHeaders?.get("x-d-token")
                                val sessionId = request?.requestHeaders?.get("saa-session-id")
                                
                                if (authHeader != null && authHeader.startsWith("Bearer ") && authHeader.length > 20) {
                                    
                                    var finalSessionId = sessionId
                                    
                                    // إذا لم نجدها في الترويسات، نبحث عنها في الكوكيز
                                    if (finalSessionId.isNullOrEmpty()) {
                                        val cookies = android.webkit.CookieManager.getInstance().getCookie("https://www.saudia.com")
                                        if (cookies != null) {
                                            for (cookie in cookies.split(";")) {
                                                if (cookie.trim().startsWith("saa-session-id=")) {
                                                    finalSessionId = cookie.substringAfter("=").trim()
                                                    break
                                                }
                                            }
                                        }
                                    }
                                    
                                    if (finalSessionId.isNullOrEmpty()) {
                                        finalSessionId = java.util.UUID.randomUUID().toString()
                                    }

                                    viewModel.saveTokens(authHeader, xdToken ?: "", finalSessionId)
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
                    }
                    loadUrl("https://www.saudia.com/ar-SA")
                }
            }
        )
    }
}
