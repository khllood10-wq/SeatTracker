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
                            
                            if (!hasIntercepted && url.contains("saudia.com")) {
                                val authHeader = request?.requestHeaders?.get("Authorization")
                                val xdToken = request?.requestHeaders?.get("x-d-token")
                                
                                if (authHeader != null && authHeader.startsWith("Bearer ") && authHeader.length > 20) {
                                    
                                    // 🔥 سحب الكوكيز من المتصفح
                                    val cookieManager = android.webkit.CookieManager.getInstance()
                                    val webCookies = cookieManager.getCookie("https://www.saudia.com") ?: ""
                                    val apiCookies = cookieManager.getCookie("https://dapi.saudia.com") ?: ""
                                    
                                    // دمج الكوكيز
                                    val allCookies = "$webCookies; $apiCookies"
                                    
                                    // محاولة استخراج saa-session-id من الكوكيز
                                    var sessionId = ""
                                    for (cookie in allCookies.split(";")) {
                                        if (cookie.trim().startsWith("saa-session-id=")) {
                                            sessionId = cookie.substringAfter("=").trim()
                                            break
                                        }
                                    }
                                    
                                    // إذا لم نجدها في الكوكيز، نأخذها من الترويسات
                                    if (sessionId.isEmpty()) {
                                        sessionId = request?.requestHeaders?.get("saa-session-id") ?: ""
                                    }

                                    // حفظ كل شيء (التوكن، الكوكيز، الجلسة)
                                    viewModel.saveTokens(authHeader, xdToken ?: "", sessionId, allCookies)
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
