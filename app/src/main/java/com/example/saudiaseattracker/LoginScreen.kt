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

    // واجهة بسيطة تظهر أثناء جلب التوكن
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text("جاري تجهيز الاتصال بالخطوط السعودية...")
        }

        // المتصفح المخفي بحجم 0x0 (لن يراه المستخدم ولكنه سيعمل في الخلفية)
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
                            // نلتقط التوكن الخاص بأي طلب يذهب للسعودية
                            if (!hasIntercepted && url.contains("saudia.com")) {
                                val authHeader = request?.requestHeaders?.get("Authorization")
                                val xdToken = request?.requestHeaders?.get("x-d-token")
                                
                                // تأكد أنه توكن حقيقي وليس فارغ
                                if (authHeader != null && authHeader.startsWith("Bearer ") && authHeader.length > 20) {
                                    viewModel.saveTokens(authHeader, xdToken ?: "")
                                    hasIntercepted = true
                                    
                                    // العودة لصفحة البحث فوراً
                                    view.post {
                                        navController.navigate(Screen.Search.route) {
                                            popUpTo(Screen.Search.route) { inclusive = true }
                                        }
                                    }
                                }
                            }
                            return super.shouldInterceptRequest(view, request)
                        }
                    }

                    // فتح الصفحة الرئيسية كضيف
                    loadUrl("https://www.saudia.com/ar-SA")
                }
            }
        )
    }
}
