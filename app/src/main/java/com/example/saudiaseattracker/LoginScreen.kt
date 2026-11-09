package com.example.saudiaseattracker

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import kotlinx.coroutines.delay

@Composable
fun LoginScreen(navController: NavController, viewModel: FlightViewModel) {
    val context = LocalContext.current
    var statusText by remember { mutableStateOf("جاري تجهيز الاتصال...") }
    var isError by remember { mutableStateOf(false) }
    var retryKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(retryKey) {
        isError = false
        statusText = "جاري تجهيز الاتصال..."
        viewModel.clearAuth()
        WebViewManager.reset()
        WebViewManager.init(context)

        var waited = 0
        while (waited < 300 &&
            (WebViewManager.capturedAuth == null || WebViewManager.capturedXD == null)
        ) {
            delay(100)
            waited++
        }

        if (WebViewManager.capturedAuth == null || WebViewManager.capturedXD == null) {
            isError = true
            statusText = "auth=${WebViewManager.capturedAuth != null} xd=${WebViewManager.capturedXD != null}"
            return@LaunchedEffect
        }

        // نحفظ كل التوكنات — الكود الأصلي
        viewModel.saveTokens(
            auth = WebViewManager.capturedAuth!!,
            xDToken = WebViewManager.capturedXD!!,
            sessionId = WebViewManager.capturedSession ?: "",
            userAgent = ""
        )

        navController.navigate(Screen.Search.route) {
            popUpTo(Screen.Login.route) { inclusive = true }
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            if (isError) {
                Text("❌", style = MaterialTheme.typography.displayLarge)
                Spacer(Modifier.height(12.dp))
                Text(statusText, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { retryKey++ }) { Text("🔄 إعادة المحاولة") }
            } else {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(statusText, fontSize = 13.sp)
            }
        }
    }
}
