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
    var loadAttempts by remember { mutableStateOf(0) } // لحماية التطبيق من حلقة التحديث اللانهائية

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text("جاري تجاوز حماية السيرفر...")
        }

        AndroidView(
            // ⚠️ تم تغيير الحجم إلى 1.dp لمنع الكراش
            modifier = Modifier.size(1.dp), 
            factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(1, 1) // إجبار المتصفح على أخذ مساحة صغيرة
                    
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    
                    // 🔥 إضافة WebChromeClient ضرورية لاستقرار WebView
                    webChromeClient = WebChromeClient() 
                    
                    android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                    android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    webViewClient = object : WebViewClient() {
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
                                    val userAgent = settings.userAgentString

                                    viewModel.saveTokens(authHeader, xdToken ?: "", finalSessionId, userAgent)
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
                            // إذا انتهى التحميل ولم نلتقط التوكن، ننتظر ثم نعيد التحميل (بحد أقصى 3 مرات)
                            if (!hasIntercepted && loadAttempts < 3) {
                                loadAttempts++
                                view?.postDelayed({ 
                                    if (!hasIntercepted) view.reload() 
                                }, 3000)
                            }
                        }
                    }
                    loadUrl("https://www.saudia.com/ar-SA")
                }
            }
        )
    }
}
