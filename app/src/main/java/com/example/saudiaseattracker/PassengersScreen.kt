package com.example.saudiaseattracker

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.webkit.WebView
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassengersScreen(
    navController: NavController,
    viewModel: FlightViewModel
) {
    val cartId = viewModel.cartId.collectAsStateWithLifecycle().value
    val rawCartResponse = viewModel.rawCartResponse.collectAsStateWithLifecycle().value.orEmpty()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        if (viewModel.cartId.value.isNullOrEmpty()) {
            viewModel.createCartAndProceedToPassenger()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        
        // شريط أدوات صغير أعلى الشاشة للتحكم (تحديث ونسخ)
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("الـ API Response", style = MaterialTheme.typography.titleMedium)
            Row {
                IconButton(onClick = { 
                    viewModel.createCartAndProceedToPassenger() 
                }) {
                    Icon(Icons.Default.Refresh, contentDescription = "تحديث")
                }
                IconButton(onClick = {
                    val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cb.setPrimaryClip(ClipData.newPlainText("CartJSON", rawCartResponse))
                    Toast.makeText(context, "تم النسخ!", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "نسخ")
                }
            }
        }

        // ═══ عرض الـ WebView ═══
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                }
            },
            update = { webView ->
                // بمجرد وصول الـ response، نعرضه داخل الـ WebView
                if (rawCartResponse.isNotEmpty()) {
                    val html = """
                        <html>
                        <head>
                        <meta name="viewport" content="width=device-width, initial-scale=1.0">
                        <style>
                            body { font-family: monospace; font-size: 11px; padding: 12px; white-space: pre-wrap; word-wrap: break-word; color: #333; background-color: #f9f9f9; }
                        </style>
                        </head>
                        <body>
                        $rawCartResponse
                        </body>
                        </html>
                    """.trimIndent()
                    webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                } else {
                    webView.loadDataWithBaseURL(null, "<html><body><h3>جاري جلب البيانات...</h3></body></html>", "text/html", "UTF-8", null)
                }
            }
        )
    }
}
