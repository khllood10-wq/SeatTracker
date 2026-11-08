package com.example.saudiaseattracker

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(navController: NavController, viewModel: FlightViewModel) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("تسجيل الدخول للخطوط السعودية") })
        }
    ) { padding ->
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(padding),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // نسمح بالكوكيز عشان الجلسة تثبت
                    android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                    android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    // هذا هو "الجاسوس" الذي يلتقط التوكن
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: android.webkit.WebResourceRequest?
                        ): android.webkit.WebResourceResponse? {
                            val url = request?.url.toString()
                            // نراقب أي طلب يذهب إلى سيرفرات الخطوط السعودية
                            if (url.contains("saudia.com") || url.contains("dapi.saudia.com")) {
                                val authHeader = request?.requestHeaders?.get("Authorization")
                                val xdToken = request?.requestHeaders?.get("x-d-token")
                                
                                if (authHeader != null && authHeader.startsWith("Bearer ")) {
                                    // وجدنا التوكن! نرسله للـ ViewModel ليحفظه
                                    viewModel.saveTokens(authHeader, xdToken ?: "")
                                }
                            }
                            return super.shouldInterceptRequest(view, request)
                        }
                    }

                    // نفتح صفحة تسجيل الدخول (أو الصفحة الرئيسية التي تطلب تسجيل الدخول)
                    loadUrl("https://www.saudia.com/ar-SA")
                }
            }
        )
    }
}
