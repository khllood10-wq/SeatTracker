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
fun LoginScreen(
    navController: NavController,
    viewModel: FlightViewModel
) {
    val context = LocalContext.current
    var statusText by remember { mutableStateOf("جاري تجهيز الاتصال...") }
    var isError by remember { mutableStateOf(false) }
    var retryKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(retryKey) {
        isError = false
        statusText = "جاري تجهيز الاتصال..."

        // 1. مسح كل بيانات المصادقة القديمة
        viewModel.clearAuth()

        // 2. تصفير WebView القديم وفتح جديد
        WebViewManager.reset()
        WebViewManager.init(context)

        // 3. انتظار التقاط الـ auth (حد أقصى 25 ثانية)
        var waited = 0
        while (waited < 250 && WebViewManager.capturedAuth == null) {
            delay(100)
            waited++
        }

        // 4. لو ما التقطش → خطأ
        if (WebViewManager.capturedAuth == null) {
            isError = true
            statusText = "تعذّر التقاط التوكن بعد 25 ثانية"
            return@LaunchedEffect
        }

        // 5. نحفظ auth فقط — بدون x-d-token وبدون session-id
        viewModel.saveTokens(
            auth = WebViewManager.capturedAuth!!,
            xDToken = "",
            sessionId = "",
            userAgent = ""
        )

        // 6. الانتقال لصفحة البحث
        navController.navigate(Screen.Search.route) {
            popUpTo(Screen.Login.route) { inclusive = true }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            if (isError) {
                Text(
                    text = "❌",
                    style = MaterialTheme.typography.displayLarge
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = statusText,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { retryKey++ }) {
                    Text("🔄 إعادة المحاولة")
                }
            } else {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = statusText,
                    fontSize = 13.sp
                )
            }
        }
    }
}
