package com.example.saudiaseattracker

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(navController: NavController, viewModel: FlightViewModel) {
    val context = LocalContext.current
    var capturedToken by remember { mutableStateOf<String?>(null) }

    // تهيئة WebViewManager مرة واحدة
    LaunchedEffect(Unit) {
        WebViewManager.init(context)
    }

    LaunchedEffect(capturedToken) {
        if (capturedToken != null) {
            viewModel.saveTokens(
                auth = capturedToken!!,
                xDToken = "",
                sessionId = "",
                userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )
            navController.navigate(Screen.Search.route) {
                popUpTo(Screen.Login.route) { inclusive = true }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("جاري تجهيز الاتصال...", style = MaterialTheme.typography.titleLarge)

        // WebView مخفي 0.dp x 0.dp — لجلب التوكن فقط
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = android.view.ViewGroup.LayoutParams(0, 0)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString =
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: android.webkit.WebResourceRequest?
                        ): android.webkit.WebResourceResponse? {
                            val authHeader = request?.requestHeaders?.get("Authorization")
                            if (authHeader != null && authHeader.startsWith("Bearer ") && capturedToken == null) {
                                capturedToken = authHeader
                                Log.d("TOKEN", "Captured: ${authHeader.take(30)}...")
                            }
                            return super.shouldInterceptRequest(view, request)
                        }
                    }
                    loadUrl("https://www.saudia.com/ar-SA/booking/passengerDetails")
                }
            },
            modifier = Modifier.size(0.dp)
        )
    }
}
