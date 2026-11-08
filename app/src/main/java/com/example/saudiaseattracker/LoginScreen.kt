package com.example.saudiaseattracker

import android.annotation.SuppressLint
import android.webkit.CookieManager
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
                    
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: android.webkit.WebResourceRequest?
                        ): android.webkit.WebResourceResponse? {
                            val url = request?.url.toString()
                            
                            if (!hasIntercepted && url.contains("saudia.com")) {
                                val authHeader = request?.requestHeaders?.get("Authorization")
                                val xdToken = request?.requestHeaders?.get("x-d-token")
                                
                                if (authHeader != null && authHeader.startsWith("Bearer ") && authHeader.length > 20) {
                                    
                                    // محاولة استخراج رقم الجلسة من الكوكيز
                                    val cookies = CookieManager.getInstance().getCookie("https://www.saudia.com")
                                    var sessionId = ""
                                    
                                    // إذا لقينا كوكيز، نبحث عن saa-session-id أو نولد واحد وهمي
                                    if (cookies != null) {
                                        val cookieList = cookies.split(";")
                                        for (cookie in cookieList) {
                                            if (cookie.trim().startsWith("saa-session-id=")) {
                                                sessionId = cookie.substringAfter("=").trim()
                                                break
                                            }
                                        }
                                    }
                                    
                                    // إذا ما لقينا كوكي، نولد رقم عشوائي عشان نتجاوز الجدار
                                    if (sessionId.isEmpty()) {
                                        sessionId = java.util.UUID.randomUUID().toString()
                                    }

                                    viewModel.saveTokens(authHeader, xdToken ?: "", sessionId)
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
