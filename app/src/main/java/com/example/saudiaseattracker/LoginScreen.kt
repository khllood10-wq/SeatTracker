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
    var capturedAuth by remember { mutableStateOf<String?>(null) }
    var capturedXD by remember { mutableStateOf<String?>(null) }
    var capturedSession by remember { mutableStateOf<String?>(null) }
    var capturedUA by remember { mutableStateOf<String?>(null) }
    var hasNavigated by remember { mutableStateOf(false) }

    // تهيئة WebViewManager للاستخدام اللاحق
    LaunchedEffect(Unit) {
        WebViewManager.init(context)
    }

    LaunchedEffect(capturedAuth) {
        if (capturedAuth != null && !hasNavigated) {
            hasNavigated = true
            viewModel.saveTokens(
                auth = capturedAuth!!,
                xDToken = capturedXD ?: "",
                sessionId = capturedSession ?: "",
                userAgent = capturedUA ?: ""
            )
            navController.navigate(Screen.Search.route) {
                popUpTo(Screen.Login.route) { inclusive = true }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("جاري تجهيز الاتصال...", style = MaterialTheme.typography.titleLarge)
            if (capturedAuth == null) {
                Spacer(Modifier.height(8.dp))
                CircularProgressIndicator()
                Spacer(Modifier.height(8.dp))
                Text("بانتظار التوكن...", style = MaterialTheme.typography.bodySmall)
            }
        }

        // WebView مخفي 0.dp — يلتقط كل التوكنات المطلوبة
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = android.view.ViewGroup.LayoutParams(0, 0)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString =
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: android.webkit.WebResourceRequest?
                        ): android.webkit.WebResourceResponse? {
                            val headers = request?.requestHeaders ?: emptyMap()
                            val auth = headers["Authorization"]
                            if (auth != null && auth.startsWith("Bearer ") && capturedAuth == null) {
                                capturedAuth = auth
                                capturedXD = headers["x-d-token"]
                                capturedSession = headers["saa-session-id"]
                                capturedUA = headers["User-Agent"]
                                Log.d("TOKEN", "Auth: ${auth.take(30)}...")
                                Log.d("TOKEN", "XD: ${capturedXD?.take(30)}...")
                                Log.d("TOKEN", "Session: $capturedSession")
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
